package com.rfl;

import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;

/**
 * Owns one gzipped NDJSON replay file. {@link #write} serialises the line with Gson on the
 * caller's thread, then queues the resulting string onto the injected executor, which does the
 * actual gzip/file IO — never the RuneLite client thread.
 *
 * <p>The injected executor is shared with the rest of the plugin and is not guaranteed to be
 * single-threaded or to run tasks in submission order, so ordering is this class's own
 * responsibility: every step ({@link #doOpen}, {@link #doWrite}, {@link #doClose}) is appended
 * to {@link #steps}, a FIFO queue, and only one {@link #drain} task is ever active on the
 * executor at a time (guarded by {@link #draining}). That single drain task runs steps strictly
 * in the order they were queued, however many worker threads the executor has, which also
 * batches a run of rapid writes (e.g. 50 Hz sampling) into far fewer executor tasks than one
 * per write.
 *
 * <p>After any IO failure, the writer logs once, closes what it can and marks itself not open;
 * every later {@link #write} call is then a no-op rather than retrying every frame. The failure
 * shows as {@link ReplayState#ERROR} with a short {@link #error reason} until the next open.
 *
 * <p>Transparency for the panel ({@link #state}, {@link #progress}): every queued line adds its
 * size to {@link #queuedBytes} and, once its step has run, to {@link #writtenBytes}; both only
 * ever grow. {@link #close} notes both at that moment, so the saving progress is the share of the
 * lines still queued at close that have since been written, and reaches 1.0 only once the gzip
 * trailer is on disk ({@link ReplayState#SAVED}). A deferred line's size is unknown until the
 * drain serialises it, so it counts as {@link #DEFERRED_WEIGHT} on both sides.
 */
@Slf4j
final class ReplayWriter
{
    private final Gson gson;
    private final ExecutorService executor;

    /** Pending steps, FIFO. Only ever drained by the single active {@link #drain} task. */
    private final Queue<Runnable> steps = new ConcurrentLinkedQueue<>();
    /** True while a {@link #drain} task is scheduled or running. Guards against two at once. */
    private final AtomicBoolean draining = new AtomicBoolean(false);

    /** Set and read only on the drain task. */
    private volatile Writer out;
    /** True from {@link #open} until {@link #close} or a write failure; read from any thread. */
    private volatile boolean open;
    private final AtomicLong bytesWritten = new AtomicLong();
    private final AtomicLong deferredLines = new AtomicLong();
    private final AtomicLong deferredBytes = new AtomicLong();

    /** Progress weight of one deferred (model) line, about a mid-sized recorded model. */
    static final long DEFERRED_WEIGHT = 16 * 1024;
    /** Lifetime totals, never reset: see the class comment. */
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicLong writtenBytes = new AtomicLong();
    /** {@link #writtenBytes} and {@link #queuedBytes} when the current save began. */
    private volatile long saveFrom;
    private volatile long saveTo;
    private final AtomicReference<ReplayState> state = new AtomicReference<>(ReplayState.IDLE);
    private volatile String error;
    /** Compressed bytes of the current file on disk so far; reset by each open, on the drain. */
    private final AtomicLong fileBytes = new AtomicLong();
    /** The finished file's size and when it finished, set as the save completes. */
    private volatile long savedBytes;
    private volatile long savedAtMs;

    ReplayWriter(Gson gson, ExecutorService executor)
    {
        this.gson = gson;
        this.executor = executor;
    }

    /** Opens a new file (closing any open one). Creates parent dirs. Runs on the executor. */
    void open(Path file)
    {
        closeQuietly();
        open = true;
        error = null;
        state.set(ReplayState.RECORDING);
        bytesWritten.set(0);
        deferredLines.set(0);
        deferredBytes.set(0);
        enqueue(() -> doOpen(file));
    }

    /** Serialises the line with Gson on the caller's thread; the write happens on the executor. */
    void write(Object line)
    {
        if (!open)
        {
            return;
        }
        String json = gson.toJson(line);
        long weight = json.length() + 1;
        queuedBytes.addAndGet(weight);
        enqueue(() ->
        {
            try
            {
                doWrite(json);
            }
            finally
            {
                writtenBytes.addAndGet(weight);
            }
        });
    }

    /**
     * Like {@link #write}, but serialises on the drain task instead of the caller's thread, for
     * large lines (recorded models) the caller never touches again: the line and every array in
     * it must be immutable from this call on. Order relative to {@link #write} is kept.
     */
    void writeDeferred(Object line)
    {
        writeDeferred(line, true);
    }

    /**
     * {@link #writeDeferred(Object)}; {@code model} false keeps the line out of the
     * {@link #deferredLines} / {@link #deferredBytes} debug counts (a big non-model line such as
     * {@code pitch}).
     */
    void writeDeferred(Object line, boolean model)
    {
        if (!open)
        {
            return;
        }
        queuedBytes.addAndGet(DEFERRED_WEIGHT);
        enqueue(() ->
        {
            try
            {
                String json = gson.toJson(line);
                if (model)
                {
                    deferredLines.incrementAndGet();
                    deferredBytes.addAndGet(json.length() + 1);
                }
                doWrite(json);
            }
            finally
            {
                writtenBytes.addAndGet(DEFERRED_WEIGHT);
            }
        });
    }

    /** Lines written through {@link #writeDeferred} since the last {@link #open}, for debug output. */
    long deferredLines()
    {
        return deferredLines.get();
    }

    /** Uncompressed bytes of those lines, for debug output. */
    long deferredBytes()
    {
        return deferredBytes.get();
    }

    /**
     * Finishes the gzip (trailer) and closes. Safe to call when nothing is open. The returned
     * future completes once the queued close step has run, so the file is complete on disk; it is
     * queued right behind the close, and a failing step never stops later steps running.
     */
    CompletableFuture<Void> close()
    {
        // Only a file being recorded starts a save; closing nothing, or a failed file, keeps the state.
        boolean saving = false;
        if (state.get() == ReplayState.RECORDING)
        {
            saveFrom = writtenBytes.get();
            saveTo = queuedBytes.get();
            saving = state.compareAndSet(ReplayState.RECORDING, ReplayState.SAVING);
        }
        closeQuietly();
        CompletableFuture<Void> done = new CompletableFuture<>();
        final boolean finishes = saving;
        enqueue(() ->
        {
            if (finishes)
            {
                savedBytes = fileBytes.get();
                savedAtMs = System.currentTimeMillis();
                // Fails, correctly, when the save failed or a new file opened meanwhile.
                state.compareAndSet(ReplayState.SAVING, ReplayState.SAVED);
            }
            done.complete(null);
        });
        return done;
    }

    boolean isOpen()
    {
        return open;
    }

    /** Uncompressed bytes written so far, for debug output. */
    long bytesWritten()
    {
        return bytesWritten.get();
    }

    /** Any thread. */
    ReplayState state()
    {
        return state.get();
    }

    /** Any thread: why the last file failed, or null. Short and without the path. */
    String error()
    {
        return error;
    }

    /**
     * Any thread: how far the current save is, 0..1. 1.0 only once {@link ReplayState#SAVED};
     * while {@link ReplayState#SAVING} it stops short of 1 until the trailer is written; 0 otherwise.
     */
    double progress()
    {
        ReplayState s = state.get();
        if (s == ReplayState.SAVED)
        {
            return 1.0;
        }
        if (s != ReplayState.SAVING)
        {
            return 0.0;
        }
        long from = saveFrom;
        long total = saveTo - from;
        if (total <= 0)
        {
            return 0.99;
        }
        double done = (writtenBytes.get() - from) / (double) total;
        return Math.max(0.0, Math.min(0.99, done));
    }

    /** Lifetime total of line weights queued, for progress. */
    long queuedBytes()
    {
        return queuedBytes.get();
    }

    /** Lifetime total of line weights whose step has run, for progress. */
    long writtenBytes()
    {
        return writtenBytes.get();
    }

    /** Compressed bytes of the current (or just closed) file on disk so far. */
    long fileBytes()
    {
        return fileBytes.get();
    }

    /** Size on disk of the last file that finished saving. */
    long savedBytes()
    {
        return savedBytes;
    }

    /** Epoch ms the last save finished, 0 before any. */
    long savedAtMs()
    {
        return savedAtMs;
    }

    private void closeQuietly()
    {
        boolean wasOpen = open;
        open = false;
        enqueue(() -> doClose(wasOpen));
    }

    /**
     * Queues one step and, if nothing is currently draining, submits the drain task.
     * Package-private so tests can inject a throwing step without a real IO failure.
     */
    void enqueue(Runnable step)
    {
        steps.add(step);
        if (draining.compareAndSet(false, true))
        {
            executor.execute(this::drain);
        }
    }

    /**
     * Runs queued steps in order until the queue is empty, then clears {@link #draining}. Races
     * one more look at the queue after clearing it: a step can land between the last {@code
     * poll} and the flag being cleared, and nothing else will schedule a drain for it once
     * {@link #draining} reads true to that producer, so this task reclaims the flag itself
     * instead of leaving that step stranded (a lost wake-up).
     *
     * <p>A step is never allowed to escape: if one throws, that's treated exactly like an
     * {@link IOException} from inside the step itself — logged once, writer marked failed and
     * closed quietly — and draining carries straight on to the next step. Letting the exception
     * out instead would skip {@link #draining}'s reset, wedging it {@code true} forever: no
     * later {@link #enqueue} would ever submit another drain task, so every subsequent write
     * would just pile up in {@link #steps} unbounded, silently.
     */
    private void drain()
    {
        while (true)
        {
            Runnable step;
            while ((step = steps.poll()) != null)
            {
                try
                {
                    step.run();
                }
                catch (RuntimeException e)
                {
                    log.warn("RFL replay: step failed, stopping", e);
                    fail(e);
                    closeOut();
                }
            }
            draining.set(false);
            if (steps.isEmpty() || !draining.compareAndSet(false, true))
            {
                return;
            }
        }
    }

    /** Drain task only. */
    private void doOpen(Path file)
    {
        try
        {
            fileBytes.set(0);
            Files.createDirectories(file.getParent());
            out = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(new Counting(Files.newOutputStream(file), fileBytes)), StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            log.warn("RFL replay: can't open {}", file, e);
            out = null;
            fail(e);
        }
    }

    /** Drain task only. */
    private void doWrite(String json)
    {
        if (out == null)
        {
            return;
        }
        try
        {
            out.write(json);
            out.write('\n');
            bytesWritten.addAndGet(json.length() + 1);
        }
        catch (IOException e)
        {
            log.warn("RFL replay: write failed, stopping", e);
            fail(e);
            closeOut();
        }
    }

    /**
     * Drain task only. Always closes whatever is open: a failure on the previous file can mark the
     * writer closed while this file's open is already queued, and skipping the close then would
     * leak the handle and leave a header-only gzip. {@link #closeOut} is a no-op when nothing is.
     */
    private void doClose(boolean wasOpen)
    {
        closeOut();
    }

    /** Drain task only. */
    private void closeOut()
    {
        Writer w = out;
        out = null;
        if (w == null)
        {
            return;
        }
        try
        {
            w.close();
        }
        catch (IOException e)
        {
            log.warn("RFL replay: close failed", e);
            fail(e);
        }
    }

    /** Any thread: marks the writer failed, for {@link #state} and {@link #error}. */
    private void fail(Exception e)
    {
        open = false;
        error = reason(e);
        state.set(ReplayState.ERROR);
    }

    /**
     * A short reason for the panel: a file-system error's own reason, or its type in words
     * ("access denied") rather than its message, which is often just the path.
     */
    static String reason(Throwable e)
    {
        if (e instanceof FileSystemException)
        {
            String r = ((FileSystemException) e).getReason();
            if (r != null && !r.isEmpty())
            {
                return r;
            }
            return words(e.getClass().getSimpleName());
        }
        String m = e.getMessage();
        return m == null || m.isEmpty() ? words(e.getClass().getSimpleName()) : m;
    }

    /** "AccessDeniedException" to "access denied". */
    private static String words(String type)
    {
        String base = type.endsWith("Exception") ? type.substring(0, type.length() - "Exception".length()) : type;
        return base.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    }

    /** Counts the bytes that reach the file, so the panel can show the compressed size. */
    private static final class Counting extends FilterOutputStream
    {
        private final AtomicLong count;

        Counting(OutputStream out, AtomicLong count)
        {
            super(out);
            this.count = count;
        }

        @Override
        public void write(int b) throws IOException
        {
            out.write(b);
            count.incrementAndGet();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException
        {
            out.write(b, off, len);
            count.addAndGet(len);
        }
    }
}
