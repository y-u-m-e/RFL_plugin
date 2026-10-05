package sh.yumekui.toolkit.io;

import sh.yumekui.toolkit.concurrent.SerialQueue;

import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;

import sh.yumekui.toolkit.text.FileNameTemplate;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns one gzipped NDJSON file (one JSON value per line) at a time. {@link #write} serialises the line with Gson on the
 * caller's thread, then queues the resulting string; the gzip and file IO run on the writer's own
 * single-thread executor, never the RuneLite client thread and never RuneLite's shared executor
 * (a big line or a slow disk would otherwise hold up every other plugin's scheduled work).
 *
 * <p>Ordering: every step ({@link #doOpen}, a write, {@link #doClose}) goes through one
 * {@link SerialQueue}, so steps run strictly in the order they were queued whatever executor
 * runs them. The executor is created on first use and shut down by {@link #shutdown} once every
 * queued step has run; a later open creates a fresh one.
 *
 * <p>Backpressure: lines waiting for the disk are capped at {@link #MAX_PENDING_BYTES}. A disk too
 * slow to keep up (antivirus, a network drive) fails the current file cleanly, with what was
 * queued still written and the gzip trailer added, instead of growing memory until the client
 * runs out.
 *
 * <p>After any IO failure the writer logs once, closes what it can and marks itself not open;
 * every later {@link #write} is a no-op until the next open. The failure shows as
 * {@link WriterState#ERROR} with a short {@link #error reason}.
 *
 * <p>Transparency for the panel ({@link #state}, {@link #progress}): every queued line adds its
 * size to {@link #queuedBytes} and, once its step has run, to {@link #writtenBytes}; both only
 * ever grow. {@link #close} notes both at that moment, so the saving progress is the share of the
 * lines still queued at close that have since been written, and reaches 1.0 only once the gzip
 * trailer is on disk ({@link WriterState#SAVED}). A deferred line's size is unknown until the
 * drain serialises it, so it counts as {@link #DEFERRED_WEIGHT} on both sides.
 */
@Slf4j
public final class GzipNdjsonWriter
{
    /**
     * How often, in wall-clock ms, the drain sync-flushes the gzip stream while writing. A crash
     * (client killed) then loses at most this much; everything before the last flush decompresses.
     */
    public static final long FLUSH_EVERY_MS = 2_000;
    /** Gives up numbering after this many taken names. */
    public static final int MAX_NUMBER = 999;
    /** Progress weight of one deferred line, whose real size is unknown until it is serialised. */
    public static final long DEFERRED_WEIGHT = 16 * 1024;
    /** Progress shown while saving, until the trailer is on disk: never 100% before it is done. */
    private static final double ALMOST_DONE = 0.99;
    /**
     * Most bytes of lines that may wait for the disk at once. Even 50 lines a second of a few KB is
     * minutes of backlog at 64 MiB, so only a stalled disk reaches it.
     */
    public static final long MAX_PENDING_BYTES = 64L * 1024 * 1024;
    /** The panel's reason when {@link #MAX_PENDING_BYTES} is reached. */
    public static final String DISK_TOO_SLOW = "the disk fell too far behind, so writing stopped";

    private final Gson gson;
    private final String fileSuffix;
    private final Supplier<ExecutorService> executors;
    private final LongSupplier clock;
    private final SerialQueue queue;
    private final long maxPendingBytes;

    /** The executor steps run on; created on first use, dropped by {@link #shutdown}. Guarded by this. */
    private ExecutorService executor;
    /** Drain task only: when the gzip stream was last sync-flushed. */
    private long lastFlushMs;

    /** Set and read only on the drain task. */
    private volatile Writer out;
    /**
     * The current file: the path asked for at {@link #open}, then the one actually created, which
     * has {@code -2}, {@code -3}, ... when the name was already taken. Read from any thread.
     */
    private volatile Path file;
    /** The last file actually created; set on the drain task, so steps queued behind it see their own. */
    private volatile Path created;
    /** True from {@link #open} until {@link #close} or a failure; read from any thread. */
    private volatile boolean open;
    private final AtomicLong bytesWritten = new AtomicLong();
    private final AtomicLong deferredLines = new AtomicLong();
    private final AtomicLong deferredBytes = new AtomicLong();

    /** Lifetime totals, never reset: see the class comment. */
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicLong writtenBytes = new AtomicLong();
    /** {@link #writtenBytes} and {@link #queuedBytes} when the current save began. */
    private volatile long saveFrom;
    private volatile long saveTo;
    private final AtomicReference<WriterState> state = new AtomicReference<>(WriterState.IDLE);
    private volatile String error;
    /** Compressed bytes of the current file on disk so far; reset by each open, on the drain. */
    private final AtomicLong fileBytes = new AtomicLong();
    /** The finished file's size and when it finished, set as the save completes. */
    private volatile long savedBytes;
    private volatile long savedAtMs;

    /**
     * Steps run on a daemon thread of the writer's own, named {@code threadName}.
     *
     * @param fileSuffix the file names' fixed ending (".ndjson.gz"); a taken name is numbered before it
     */
    public GzipNdjsonWriter(Gson gson, String fileSuffix, String threadName)
    {
        this(gson, fileSuffix, () -> newWriterThread(threadName), System::currentTimeMillis, MAX_PENDING_BYTES);
    }

    /**
     * Full control, for tests.
     *
     * @param executors makes the executor steps run on, each time one is needed after a shutdown
     * @param clock wall-clock ms for the periodic sync flush
     * @param maxPendingBytes the backlog cap ({@link #MAX_PENDING_BYTES} by default)
     */
    public GzipNdjsonWriter(Gson gson, String fileSuffix, Supplier<ExecutorService> executors, LongSupplier clock,
        long maxPendingBytes)
    {
        this.gson = gson;
        this.fileSuffix = fileSuffix;
        this.executors = executors;
        this.clock = clock;
        this.maxPendingBytes = maxPendingBytes;
        this.queue = new SerialQueue(this::dispatch, this::stepFailed);
    }

    private static ExecutorService newWriterThread(String threadName)
    {
        return Executors.newSingleThreadExecutor(task ->
        {
            Thread thread = new Thread(task, threadName);
            // Never holds the JVM open: the client's exit waits on the close future instead.
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Hands the queue's drain task to the current executor, creating one if the last was shut down.
     * Synchronized with {@link #shutdown}, so a task is never handed to an executor already shut down.
     */
    private synchronized void dispatch(Runnable drainTask)
    {
        if (executor == null)
        {
            executor = executors.get();
        }
        executor.execute(drainTask);
    }

    /**
     * Shuts the executor down once every step queued so far has run, so the thread goes away with
     * the plugin. The future completes after the last of them. A later {@link #open} starts a new one.
     */
    public CompletableFuture<Void> shutdown()
    {
        CompletableFuture<Void> done = new CompletableFuture<>();
        queue.enqueue(() -> done.complete(null));
        synchronized (this)
        {
            if (executor != null)
            {
                // Lets the drain task already submitted finish, then ends the thread.
                executor.shutdown();
                executor = null;
            }
        }
        return done;
    }

    /**
     * Drain task only: sync-flushes the gzip stream when {@link #FLUSH_EVERY_MS} has passed since the
     * last one, so a killed client loses at most that much and everything before it inflates.
     */
    private void maybeFlush() throws IOException
    {
        long now = clock.getAsLong();
        if (now - lastFlushMs >= FLUSH_EVERY_MS)
        {
            out.flush();
            lastFlushMs = now;
        }
    }

    /**
     * Opens a new file (closing any open one). Creates parent dirs. Runs on the executor. Never
     * overwrites: a taken name gets {@code -2}, {@code -3}, ... ({@link FileNameTemplate#numbered}).
     */
    public void open(Path file)
    {
        closeQuietly();
        this.file = file;
        open = true;
        error = null;
        state.set(WriterState.WRITING);
        bytesWritten.set(0);
        deferredLines.set(0);
        deferredBytes.set(0);
        enqueue(() -> doOpen(file));
    }

    /** Serialises the line with Gson on the caller's thread; the write happens on the executor. */
    public void write(Object line)
    {
        if (!open)
        {
            return;
        }
        writeJson(gson.toJson(line));
    }

    /**
     * Like {@link #write} for a line that is already JSON (one built by hand, such as a {@code team}
     * line, whose {@code null} Gson would drop). Same queue, same order.
     */
    public void writeJson(String json)
    {
        if (!open || json == null)
        {
            return;
        }
        // One per char plus the newline: the progress weight, not the exact UTF-8 size.
        long weight = json.length() + 1;
        if (!reserve(weight))
        {
            return;
        }
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
     * large lines the caller never touches again: the line and every array in
     * it must be immutable from this call on. Order relative to {@link #write} is kept.
     */
    public void writeDeferred(Object line)
    {
        writeDeferred(line, true);
    }

    /**
     * {@link #writeDeferred(Object)}; {@code counted} false keeps the line out of the
     * {@link #deferredLines} / {@link #deferredBytes} debug counts (a one-off big line that would skew
     * the average of the many counted ones).
     */
    public void writeDeferred(Object line, boolean counted)
    {
        if (!open || !reserve(DEFERRED_WEIGHT))
        {
            return;
        }
        enqueue(() ->
        {
            try
            {
                String json = gson.toJson(line);
                if (counted)
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

    /**
     * Counts a line of {@code weight} as queued, or, when that would put the backlog over the cap,
     * fails the file instead (the disk can't keep up) and returns false.
     */
    private boolean reserve(long weight)
    {
        long pending = queuedBytes.get() - writtenBytes.get();
        if (pending + weight > maxPendingBytes)
        {
            log.warn("Gzip writer: {} bytes waiting for the disk, failing the file", pending);
            error = DISK_TOO_SLOW;
            open = false;
            state.set(WriterState.ERROR);
            // What is already queued still goes out, then the trailer: the file stays readable.
            closeQuietly();
            return false;
        }
        queuedBytes.addAndGet(weight);
        return true;
    }

    /** Lines written through {@link #writeDeferred} since the last {@link #open}, for debug output. */
    public long deferredLines()
    {
        return deferredLines.get();
    }

    /** Uncompressed bytes of those lines, for debug output. */
    public long deferredBytes()
    {
        return deferredBytes.get();
    }

    /**
     * Finishes the gzip (trailer) and closes. Safe to call when nothing is open. The returned
     * future completes once the queued close step has run, so the file is complete on disk; it is
     * queued right behind the close, and a failing step never stops later steps running.
     */
    public CompletableFuture<Void> close()
    {
        // Only a file being recorded starts a save; closing nothing, or a failed file, keeps the state.
        boolean saving = false;
        if (state.get() == WriterState.WRITING)
        {
            saveFrom = writtenBytes.get();
            saveTo = queuedBytes.get();
            saving = state.compareAndSet(WriterState.WRITING, WriterState.SAVING);
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
                state.compareAndSet(WriterState.SAVING, WriterState.SAVED);
            }
            done.complete(null);
        });
        return done;
    }

    public boolean isOpen()
    {
        return open;
    }

    /** Uncompressed bytes written so far, for debug output. */
    public long bytesWritten()
    {
        return bytesWritten.get();
    }

    /** Any thread. */
    public WriterState state()
    {
        return state.get();
    }

    /** Any thread: why the last file failed, or null. Short and without the path. */
    public String error()
    {
        return error;
    }

    /**
     * Any thread: how far the current save is, 0..1. 1.0 only once {@link WriterState#SAVED};
     * while {@link WriterState#SAVING} it stops short of 1 until the trailer is written; 0 otherwise.
     */
    public double progress()
    {
        WriterState now = state.get();
        if (now == WriterState.SAVED)
        {
            return 1.0;
        }
        if (now != WriterState.SAVING)
        {
            return 0.0;
        }
        long from = saveFrom;
        long total = saveTo - from;
        if (total <= 0)
        {
            return ALMOST_DONE;
        }
        double done = (writtenBytes.get() - from) / (double) total;
        return Math.max(0.0, Math.min(ALMOST_DONE, done));
    }

    /** Compressed bytes of the current (or just closed) file on disk so far. */
    public long fileBytes()
    {
        return fileBytes.get();
    }

    /** Size on disk of the last file that finished saving. */
    public long savedBytes()
    {
        return savedBytes;
    }

    /** Epoch ms the last save finished, 0 before any. */
    public long savedAtMs()
    {
        return savedAtMs;
    }

    private void closeQuietly()
    {
        open = false;
        enqueue(this::doClose);
    }

    /**
     * Queues one step behind every earlier one. Package-private so tests can inject a throwing
     * step without a real IO failure.
     */
    public void enqueue(Runnable step)
    {
        queue.enqueue(step);
    }

    /** A step threw: treated exactly like an IO failure inside it. */
    private void stepFailed(RuntimeException error)
    {
        log.warn("Gzip writer: step failed, stopping", error);
        fail(error);
        closeOut();
    }

    /** Drain task only. */
    private void doOpen(Path file)
    {
        try
        {
            fileBytes.set(0);
            Files.createDirectories(file.getParent());
            // syncFlush: flush() ends the deflate block on a byte boundary, so a file cut off by a
            // crash still inflates up to its last flush.
            out = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(new Counting(createNew(file), fileBytes), true), StandardCharsets.UTF_8));
            lastFlushMs = clock.getAsLong();
        }
        catch (IOException e)
        {
            log.warn("Gzip writer: can't open {}", file, e);
            out = null;
            fail(e);
        }
    }

    /**
     * Drain task only: creates {@code wanted}, or the first free {@code -2}, {@code -3}, ... beside
     * it, atomically (CREATE_NEW), and records the one created in {@link #file}.
     */
    private OutputStream createNew(Path wanted) throws IOException
    {
        Path target = wanted;
        for (int n = 2; ; n++)
        {
            try
            {
                OutputStream stream = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
                created = target;
                // Only if no newer open replaced the file meanwhile.
                if (file == wanted)
                {
                    file = target;
                }
                return stream;
            }
            catch (FileAlreadyExistsException e)
            {
                if (n > MAX_NUMBER)
                {
                    throw e;
                }
                target = wanted.resolveSibling(FileNameTemplate.numbered(wanted.getFileName().toString(), n, fileSuffix));
            }
        }
    }

    /** Any thread: the current (or last) file's name, numbered once created; null before any open. */
    public String fileName()
    {
        Path current = file;
        return current == null ? null : current.getFileName().toString();
    }

    /** The last file created, numbered; for a step queued behind its close (the close summary). */
    public Path created()
    {
        return created;
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
            maybeFlush();
        }
        catch (IOException e)
        {
            log.warn("Gzip writer: write failed, stopping", e);
            fail(e);
            closeOut();
        }
    }

    /**
     * Drain task only. Always closes whatever is open: a failure on the previous file can mark the
     * writer closed while this file's open is already queued, and skipping the close then would
     * leak the handle and leave a header-only gzip. {@link #closeOut} is a no-op when nothing is.
     */
    private void doClose()
    {
        closeOut();
    }

    /** Drain task only. */
    private void closeOut()
    {
        Writer closing = out;
        out = null;
        if (closing == null)
        {
            return;
        }
        try
        {
            // Sync-flush first (the move to SAVING), then close, which writes the gzip trailer.
            closing.flush();
            closing.close();
        }
        catch (IOException e)
        {
            log.warn("Gzip writer: close failed", e);
            fail(e);
        }
    }

    /** Any thread: marks the writer failed, for {@link #state} and {@link #error}. */
    private void fail(Exception cause)
    {
        open = false;
        error = IoErrors.reason(cause);
        state.set(WriterState.ERROR);
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
