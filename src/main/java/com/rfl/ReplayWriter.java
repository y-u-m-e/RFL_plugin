package com.rfl;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;

/**
 * Owns one gzipped NDJSON replay file. {@link #write} serialises the line with Gson on the
 * caller's thread, then queues the resulting string onto the injected executor, which does the
 * actual gzip/file IO — never the RuneLite client thread.
 *
 * <p>After any IO failure, the writer logs once, closes what it can and marks itself not open;
 * every later {@link #write} call is then a no-op rather than retrying every frame.
 */
@Slf4j
final class ReplayWriter
{
    private final Gson gson;
    private final ExecutorService executor;

    /** Set and read only on the executor thread. */
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
        executor.execute(() -> doOpen(file));
    }

    /** Serialises the line with Gson on the caller's thread; the write happens on the executor. */
    void write(Object line)
    {
        if (!open)
        {
            return;
        }
        String json = gson.toJson(line);
        executor.execute(() -> doWrite(json));
    }

    /** Finishes the gzip (trailer) and closes. Safe to call when nothing is open. */
    void close()
    {
        closeQuietly();
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
        executor.execute(() -> doClose(wasOpen));
    }

    /** Executor only. */
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

    /** Executor only. */
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

    /** Executor only. Closing the previous file's writer is a no-op once it's already null. */
    private void doClose(boolean wasOpen)
    {
        if (!wasOpen)
        {
            return;
        }
        closeOut();
    }

    /** Executor only. */
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
