package com.rfl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Observer mode storage, on this computer only: appends one JSON line per finished
 * {@link ObservedCollision} to {@code RUNELITE_DIR/rfl/observer/YYYY-MM-DD.jsonl} (local date of
 * the collision's end) and keeps the latest {@link #RECENT} in memory for the RFL panel. Nothing
 * here is ever sent anywhere.
 *
 * <p>Threads: {@link #record} from any thread (the client thread in practice); the encoding and
 * the file write run on the injected executor, never the caller's thread.
 */
@Slf4j
@Singleton
final class ObserverLog
{
    static final int RECENT = 20;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Gson gson;
    private final ScheduledExecutorService executor;
    private final Path dir;
    /** Newest first; guarded by this. */
    private final Deque<ObservedCollision> recent = new ArrayDeque<>();
    private volatile Runnable onChange = () -> { };

    @Inject
    ObserverLog(Gson gson, ScheduledExecutorService executor)
    {
        this(gson, executor, RuneLite.RUNELITE_DIR.toPath().resolve("rfl").resolve("observer"));
    }

    ObserverLog(Gson gson, ScheduledExecutorService executor, Path dir)
    {
        this.gson = gson;
        this.executor = executor;
        this.dir = dir;
    }

    /** Runs (on the recording thread) after each {@link #record}. */
    void setOnChange(Runnable onChange)
    {
        this.onChange = onChange;
    }

    /** Folder the day files are written to. */
    Path dir()
    {
        return dir;
    }

    void record(ObservedCollision c)
    {
        synchronized (this)
        {
            recent.addFirst(c);
            while (recent.size() > RECENT)
            {
                recent.removeLast();
            }
        }
        executor.execute(() -> append(c));
        onChange.run();
    }

    /** Latest collisions, newest first. */
    synchronized List<ObservedCollision> recent()
    {
        return new ArrayList<>(recent);
    }

    /** Executor: creates the folder (for Open folder) and swallows any IO error. */
    void ensureDir()
    {
        try
        {
            Files.createDirectories(dir);
        }
        catch (IOException e)
        {
            log.warn("RFL observer: can't create {}", dir, e);
        }
    }

    private void append(ObservedCollision c)
    {
        Path file = dir.resolve(fileName(c.endMs));
        try
        {
            Files.createDirectories(dir);
            Files.write(file, (gson.toJson(c) + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e)
        {
            log.warn("RFL observer: can't write {}", file, e);
        }
    }

    /** {@code YYYY-MM-DD.jsonl} for the local date of an epoch ms. */
    static String fileName(long epochMs)
    {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate() + ".jsonl";
    }

    /** Panel row: "HH:mm:ss  A ↔ B  ball: X  overlap: N". */
    static String row(ObservedCollision c)
    {
        return TIME.format(Instant.ofEpochMilli(c.startMs).atZone(ZoneId.systemDefault()))
            + "  " + c.a + " ↔ " + c.b + "  ball: " + String.join(" & ", c.ball)
            + "  overlap: " + c.maxTriangles;
    }
}
