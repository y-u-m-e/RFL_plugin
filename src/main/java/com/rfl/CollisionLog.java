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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * The plugin's output, on this computer only: appends one JSON line per finished {@link Collision}
 * ({@code "type":"collision"}) and per {@link Incomplete} ({@code "type":"incomplete"}) to
 * {@code RUNELITE_DIR/rfl/collisions/YYYY-MM-DD.jsonl} (local date of the collision's end or the
 * incomplete), while Save collisions is on. The latest {@link #RECENT} collisions are kept in
 * memory for the debug panel either way. Nothing here is ever sent anywhere.
 *
 * <p>Threads: {@link #record} from any thread (the client thread in practice); the encoding and
 * the file write run on the injected executor, never the caller's thread. The {@link #setListener
 * listener} is called on the caller's thread, whether or not Save collisions is on.
 */
@Slf4j
@Singleton
final class CollisionLog
{
    static final int RECENT = 20;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** One incomplete, saved as its own line type. Field names are the JSON keys. */
    static final class Incomplete
    {
        final String type = "incomplete";
        final String receiver;
        /** Who the receiver was in contact with when they caught it. */
        final List<String> contacts;
        final long timeMs;
        final int tick;
        final int world;
        /** World tile under the receiver; a template coordinate, see {@link #sx}. */
        final int x;
        final int y;
        final int plane;
        /**
         * Scene tile of the same point as {@link #x}/{@link #y}, unique within the loaded house; x/y
         * are template coordinates that repeat across a house (every instance template chunk reuses
         * them).
         */
        final int sx;
        final int sy;

        Incomplete(String receiver, List<String> contacts, long timeMs, int tick, int world, int x, int y,
            int plane, int sx, int sy)
        {
            this.receiver = receiver;
            this.contacts = contacts;
            this.timeMs = timeMs;
            this.tick = tick;
            this.world = world;
            this.x = x;
            this.y = y;
            this.plane = plane;
            this.sx = sx;
            this.sy = sy;
        }
    }

    private final Gson gson;
    private final ScheduledExecutorService executor;
    /** Written on the executor after each successful append, read by the panel on the client thread. */
    private volatile long lastSavedAtMs;
    private final Path dir;
    private final BooleanSupplier save;
    /** Newest first; guarded by this. */
    private final Deque<Collision> recent = new ArrayDeque<>();
    /** Told about each recorded collision and incomplete (the replay recorder); may be null. */
    private volatile Consumer<Object> listener;

    @Inject
    CollisionLog(Gson gson, ScheduledExecutorService executor, RflConfig config)
    {
        this(gson, executor, RuneLite.RUNELITE_DIR.toPath().resolve("rfl").resolve("collisions"),
            config::saveCollisions);
    }

    /** @param save whether lines are written to disk right now (the Save collisions setting) */
    CollisionLog(Gson gson, ScheduledExecutorService executor, Path dir, BooleanSupplier save)
    {
        this.gson = gson;
        this.executor = executor;
        this.dir = dir;
        this.save = save;
    }

    /** Folder the day files are written to. */
    Path dir()
    {
        return dir;
    }

    /**
     * Called with each {@link Collision} or {@link Incomplete} passed to {@code record}, on the
     * caller's thread, whether or not Save collisions is on. Null clears it.
     */
    void setListener(Consumer<Object> listener)
    {
        this.listener = listener;
    }

    void record(Collision c)
    {
        synchronized (this)
        {
            recent.addFirst(c);
            while (recent.size() > RECENT)
            {
                recent.removeLast();
            }
        }
        write(c, c.endMs);
    }

    void record(Incomplete i)
    {
        write(i, i.timeMs);
    }

    private void write(Object line, long epochMs)
    {
        Consumer<Object> l = listener;
        if (l != null)
        {
            l.accept(line);
        }
        if (save.getAsBoolean())
        {
            executor.execute(() -> append(gson.toJson(line), epochMs));
        }
    }

    /** Latest collisions, newest first. */
    synchronized List<Collision> recent()
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
            log.warn("RFL collisions: can't create {}", dir, e);
        }
    }

    private void append(String json, long epochMs)
    {
        Path file = dir.resolve(fileName(epochMs));
        try
        {
            Files.createDirectories(dir);
            Files.write(file, (json + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            lastSavedAtMs = System.currentTimeMillis();
        }
        catch (IOException e)
        {
            log.warn("RFL collisions: can't write {}", file, e);
        }
    }

    /** Any thread: epoch ms the last line reached disk, 0 before any; for the panel's "Saved" tick. */
    long lastSavedAtMs()
    {
        return lastSavedAtMs;
    }

    /** {@code YYYY-MM-DD.jsonl} for the local date of an epoch ms. */
    static String fileName(long epochMs)
    {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate() + ".jsonl";
    }

    /** Debug panel row: "HH:mm:ss  A ↔ B  ball: X  overlap: N". */
    static String row(Collision c)
    {
        return TIME.format(Instant.ofEpochMilli(c.startMs).atZone(ZoneId.systemDefault()))
            + "  " + c.a + " ↔ " + c.b + "  ball: " + String.join(" & ", c.ball)
            + "  overlap: " + c.maxTriangles;
    }
}
