package com.rfl;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
 * every later {@link #write} call is then a no-op rather than retrying every frame.
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
        bytesWritten.set(0);
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
        enqueue(() -> doWrite(json));
    }

    /**
     * Finishes the gzip (trailer) and closes. Safe to call when nothing is open. The returned
     * future completes once the queued close step has run, so the file is complete on disk; it is
     * queued right behind the close, and a failing step never stops later steps running.
     */
    CompletableFuture<Void> close()
    {
        closeQuietly();
        CompletableFuture<Void> done = new CompletableFuture<>();
        enqueue(() -> done.complete(null));
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
                    open = false;
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
            Files.createDirectories(file.getParent());
            out = new BufferedWriter(
                new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            log.warn("RFL replay: can't open {}", file, e);
            out = null;
            open = false;
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
            open = false;
            closeOut();
        }
    }

    /** Drain task only. Closing the previous file's writer is a no-op once it's already null. */
    private void doClose(boolean wasOpen)
    {
        if (!wasOpen)
        {
            return;
        }
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
        }
    }
}
