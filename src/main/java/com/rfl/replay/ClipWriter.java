package com.rfl.replay;

import sh.yumekui.toolkit.io.GzipNdjsonWriter;
import sh.yumekui.toolkit.io.IoErrors;
import sh.yumekui.toolkit.text.FileNameTemplate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;

/**
 * Writes replay buffer clips ({@link ClipBuilder}) to the replays folder on a daemon thread of its
 * own ({@link #THREAD}): never the client thread, and never the recording's writer thread, so
 * writing a clip can't hold up the recording. One clip at a time, in the order asked.
 *
 * <p>A clip is never announced before its gzip trailer is on disk: {@link ClipStatus.State#SAVED}
 * is set only after the stream is closed. A failed clip's partial file is deleted.
 *
 * <p>Threads: {@link #requested} on the client thread; {@link #submit} on the recording writer's
 * thread; the cutting and writing on this writer's own; {@link #status} from any thread.
 */
@Slf4j
final class ClipWriter
{
    static final String THREAD = "rfl-clip-writer";
    /** The clips' folder inside the replays folder, named as is (never through the file-name sanitiser). */
    static final String FOLDER = "snapshot reviews";
    /** The panel's reason when there is nothing buffered yet. */
    static final String NOTHING_YET = "nothing recorded yet";
    /** How often, in lines, the progress is updated while writing. */
    private static final int PROGRESS_EVERY = 500;
    /** Progress shown until the trailer is on disk. */
    private static final double ALMOST_DONE = 0.99;

    private final Gson gson;
    private final Supplier<ExecutorService> executors;
    /** Created on first use, dropped by {@link #shutdown}. Guarded by this. */
    private ExecutorService executor;
    private volatile ClipStatus status = ClipStatus.IDLE;

    ClipWriter(Gson gson)
    {
        this(gson, ClipWriter::newThread);
    }

    /** Tests: steps run on the given executors. */
    ClipWriter(Gson gson, Supplier<ExecutorService> executors)
    {
        this.gson = gson;
        this.executors = executors;
    }

    private static ExecutorService newThread()
    {
        return Executors.newSingleThreadExecutor(task ->
        {
            Thread thread = new Thread(task, THREAD);
            // Never holds the JVM open: the client's exit waits on shutdown()'s future instead.
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Any thread. */
    ClipStatus status()
    {
        return status;
    }

    /** Client thread: a clip was asked for, so the panel shows "Saving clip" at once. */
    void requested()
    {
        status = new ClipStatus(ClipStatus.State.SAVING, null, 0L, 0, ClipStatus.Short.NONE, 0.0, null, 0L);
    }

    /**
     * The recording writer's thread: hands a snapshot to this writer's thread to cut and write.
     * Never throws: it runs as one of the recording writer's steps, and a throw there would fail
     * the recording.
     *
     * @param snapshot the buffer, or null when there was nothing to cut from
     * @param replays the replays folder; the clip goes in its {@link #FOLDER}
     * @param template the Clip file name setting ({@link ClipFileName})
     */
    void submit(ReplayRing.Snapshot snapshot, int clipCycles, Path replays, String template, long nowMs, ZoneId zone)
    {
        try
        {
            if (snapshot == null)
            {
                fail(NOTHING_YET);
                return;
            }
            execute(() -> write(snapshot, clipCycles, replays.resolve(FOLDER), template, nowMs, zone));
        }
        catch (RuntimeException e)
        {
            log.warn("RFL clip: couldn't start the save", e);
            fail(IoErrors.reason(e));
        }
    }

    private synchronized void execute(Runnable task)
    {
        if (executor == null)
        {
            executor = executors.get();
        }
        executor.execute(task);
    }

    /** Ends the thread once every clip handed over so far is written; completes after the last. */
    CompletableFuture<Void> shutdown()
    {
        CompletableFuture<Void> done = new CompletableFuture<>();
        synchronized (this)
        {
            if (executor == null)
            {
                done.complete(null);
                return done;
            }
            executor.execute(() -> done.complete(null));
            executor.shutdown();
            executor = null;
        }
        return done;
    }

    /** This writer's thread: plans, writes, closes (the gzip trailer), then announces. */
    private void write(ReplayRing.Snapshot snapshot, int clipCycles, Path dir, String template, long nowMs,
        ZoneId zone)
    {
        Path file = null;
        try
        {
            ClipBuilder.Plan plan = ClipBuilder.plan(snapshot, clipCycles);
            ClipStatus.Short shortened = ClipStatus.Short.valueOf(plan.shortened.name());
            Files.createDirectories(dir);
            file = createNew(dir.resolve(ClipFileName.fileName(template, facts(snapshot, plan, nowMs), zone)));
            String name = file.getFileName().toString();
            status = new ClipStatus(ClipStatus.State.SAVING, name, 0L, plan.seconds, shortened, 0.0, null, 0L);
            int total = Math.max(1, plan.lines);
            int[] written = { 0 };
            try (Writer out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                Files.newOutputStream(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)),
                StandardCharsets.UTF_8)))
            {
                ClipBuilder.write(gson, snapshot, plan, json ->
                {
                    out.write(json);
                    out.write('\n');
                    if (++written[0] % PROGRESS_EVERY == 0)
                    {
                        status = new ClipStatus(ClipStatus.State.SAVING, name, 0L, plan.seconds, shortened,
                            Math.min(ALMOST_DONE, written[0] / (double) total), null, 0L);
                    }
                });
            }
            // Closed: the trailer is on disk, so the clip is complete before it is announced.
            long size = Files.size(file);
            status = new ClipStatus(ClipStatus.State.SAVED, name, size, plan.seconds, shortened, 1.0, null,
                System.currentTimeMillis());
            log.info("RFL clip saved {} lines={} fromCyc={} toCyc={} bytes={}", name, written[0], plan.fromCyc,
                plan.toCyc, size);
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("RFL clip: save failed", e);
            deleteQuietly(file);
            fail(IoErrors.reason(e));
        }
    }

    /** The file name's facts: the source's world and recorder from its hdr, the clip's own the rest. */
    static ClipFileName.Facts facts(ReplayRing.Snapshot snapshot, ClipBuilder.Plan plan, long nowMs)
    {
        Object world = snapshot.hdr.get("world");
        Object rsn = snapshot.hdr.get("rsn");
        return new ClipFileName.Facts(nowMs, world instanceof Number ? ((Number) world).intValue() : 0,
            rsn instanceof String ? (String) rsn : null, snapshot.sourceFile, plan.seconds, plan.collision);
    }

    private void fail(String reason)
    {
        status = new ClipStatus(ClipStatus.State.ERROR, null, 0L, 0, ClipStatus.Short.NONE, 0.0,
            reason == null || reason.isEmpty() ? "unknown error" : reason, System.currentTimeMillis());
    }

    /** Creates {@code wanted}, or the first free {@code -2}, {@code -3}, ... beside it; never overwrites. */
    private static Path createNew(Path wanted) throws IOException
    {
        Path target = wanted;
        for (int n = 2; ; n++)
        {
            try (OutputStream created = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE))
            {
                return target;
            }
            catch (FileAlreadyExistsException e)
            {
                if (n > GzipNdjsonWriter.MAX_NUMBER)
                {
                    throw e;
                }
                target = wanted.resolveSibling(FileNameTemplate.numbered(wanted.getFileName().toString(), n,
                    ReplayFileName.SUFFIX));
            }
        }
    }

    private static void deleteQuietly(Path file)
    {
        if (file == null)
        {
            return;
        }
        try
        {
            Files.deleteIfExists(file);
        }
        catch (IOException e)
        {
            log.warn("RFL clip: couldn't delete the partial file {}", file, e);
        }
    }
}
