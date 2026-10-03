package com.rfl;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Projectile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.RuneLite;
import net.runelite.client.util.Text;

/**
 * Records each player-owned house visit to {@code RUNELITE_DIR/rfl/replays/<fileName>}, a gzipped
 * NDJSON file the RFL replay viewer reads, while Record replays is on. Nothing is sent anywhere.
 *
 * <p>Each ClientTick it reads every player's pose and every handegg projectile into a
 * {@link ReplaySampler}; each GameTick it adds a {@code tick} line and appearances; finished
 * collisions and interceptions arrive through {@link #onEvent} (the {@link CollisionLog} listener).
 * Every line goes to a {@link ReplayWriter}, which does the IO off the client thread.
 *
 * <p>Lifecycle: a file opens (with {@code hdr} and {@code pitch}) when {@link #records} turns true
 * and closes when it turns false, on HOPPING / LOGIN_SCREEN, and on {@link #stop}. A LOADING while
 * open queues a fresh {@code pitch}, written on the first logged-in frame after the load so it
 * holds the new scene. A file stays open through LOADING. Each file gets a fresh sampler.
 *
 * <p>Threads: all state here is client-thread only. The writer's executor work is its own.
 */
@Slf4j
@Singleton
final class ReplayRecorder
{
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    private static final int SCENE = 104;

    private final RflConfig config;
    private final ReplayWriter writer;
    private final Path dir;

    /** Non-null while a file is open. */
    private ReplaySampler sampler;
    private Path file;
    private int startCycle;
    /** Latest game cycle seen by {@link #onClientTick}, so {@link #onEvent} doesn't need the client. */
    private int lastCycle;
    private boolean pitchPending;
    private long frameNanos;
    private long frames;

    @Inject
    ReplayRecorder(RflConfig config, Gson gson, ScheduledExecutorService executor)
    {
        this(config, new ReplayWriter(gson, executor),
            RuneLite.RUNELITE_DIR.toPath().resolve("rfl").resolve("replays"));
    }

    ReplayRecorder(RflConfig config, ReplayWriter writer, Path dir)
    {
        this.config = config;
        this.writer = writer;
        this.dir = dir;
    }

    /** Recording runs with Record replays on, logged in, inside a POH. */
    static boolean records(boolean recordReplays, boolean loggedIn, boolean inPoh)
    {
        return recordReplays && loggedIn && inPoh;
    }

    /** {@code yyyy-MM-dd_HHmmss_w<world>.rflr.gz}, local time. */
    static String fileName(long epochMs, int world)
    {
        return FILE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
            + "_w" + world + ".rflr.gz";
    }

    /** Client thread, every ClientTick: opens/closes per {@link #records}, then samples the frame. */
    void onClientTick(Client client, boolean inPoh)
    {
        final long start = System.nanoTime();
        final GameState state = client.getGameState();
        // LOADING counts as logged in so a scene reload inside the house doesn't split the file.
        final boolean loggedIn = state == GameState.LOGGED_IN || state == GameState.LOADING;
        if (!records(config.recordReplays(), loggedIn, inPoh))
        {
            stop();
            return;
        }
        if (state != GameState.LOGGED_IN)
        {
            return;
        }
        lastCycle = client.getGameCycle();
        if (sampler == null)
        {
            open(client);
        }
        else if (pitchPending)
        {
            pitchPending = false;
            writePitch(client);
        }

        final List<ReplaySampler.PlayerState> players = new ArrayList<>();
        for (final Player player : client.getTopLevelWorldView().players())
        {
            final String name = player == null ? null : sanitizedName(player);
            final LocalPoint at = name == null ? null : player.getLocalLocation();
            if (at == null)
            {
                continue;
            }
            players.add(new ReplaySampler.PlayerState(name, at.getX(), at.getY(), player.getCurrentOrientation(),
                player.getAnimation(), player.getAnimationFrame(), player.getPoseAnimation(),
                player.getPoseAnimationFrame()));
        }
        final List<ReplaySampler.Ball> balls = new ArrayList<>();
        for (final Projectile p : client.getProjectiles())
        {
            if (InterceptionDetector.HANDEGG_PROJECTILES.contains(p.getId()))
            {
                balls.add(new ReplaySampler.Ball(p.getId(), p.getStartCycle(), p.getX(), p.getY(), p.getZ(),
                    p.getOrientation()));
            }
        }
        writeAll(sampler.frame(lastCycle, players, balls));
        frameNanos += System.nanoTime() - start;
        frames++;
    }

    /** Client thread, every GameTick: a {@code tick} line and any changed appearances. */
    void onGameTick(Client client)
    {
        if (sampler == null)
        {
            return;
        }
        final List<ReplaySampler.Appearance> appearances = new ArrayList<>();
        for (final Player player : client.getTopLevelWorldView().players())
        {
            final String name = player == null ? null : sanitizedName(player);
            final PlayerComposition comp = name == null ? null : player.getPlayerComposition();
            if (comp == null)
            {
                continue;
            }
            appearances.add(new ReplaySampler.Appearance(name, comp.getGender(), comp.getEquipmentIds(),
                comp.getColors()));
        }
        writeAll(sampler.tick(client.getGameCycle(), client.getTickCount(), appearances));
    }

    /** Client thread: closes on HOPPING / LOGIN_SCREEN; a LOADING while open queues a new pitch. */
    void onGameStateChanged(GameState state)
    {
        if (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN)
        {
            stop();
        }
        else if (state == GameState.LOADING && sampler != null)
        {
            pitchPending = true;
        }
    }

    /** Client thread (the {@link CollisionLog} listener): one finished Collision or Interception. */
    void onEvent(Object collisionOrInterception)
    {
        if (sampler == null || collisionOrInterception == null)
        {
            return;
        }
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", "ev");
        line.put("cyc", lastCycle);
        line.put("e", collisionOrInterception);
        writer.write(line);
    }

    /**
     * Client thread: closes the open file, if any. Safe to call when nothing is open. The future
     * completes once the gzip trailer is on disk (at once when nothing was open).
     */
    CompletableFuture<Void> stop()
    {
        if (sampler == null)
        {
            return CompletableFuture.completedFuture(null);
        }
        final CompletableFuture<Void> closed;
        if (config.debugLogging())
        {
            final Path path = file;
            final int cycles = lastCycle - startCycle;
            final long avgMicros = frames == 0 ? 0 : frameNanos / frames / 1000;
            closed = writer.close();
            // Queued behind the close, so the byte count is final when this runs.
            writer.enqueue(() -> log.info("[RFL debug] replay closed {} cycles={} bytes={} avgClientTickMicros={}",
                path, cycles, writer.bytesWritten(), avgMicros));
        }
        else
        {
            closed = writer.close();
        }
        sampler = null;
        file = null;
        pitchPending = false;
        return closed;
    }

    private void open(Client client)
    {
        final long now = System.currentTimeMillis();
        final int world = client.getWorld();
        sampler = new ReplaySampler();
        file = dir.resolve(fileName(now, world));
        startCycle = lastCycle;
        frameNanos = 0;
        frames = 0;
        pitchPending = false;
        writer.open(file);

        final Player local = client.getLocalPlayer();
        final Map<String, Object> hdr = new LinkedHashMap<>();
        hdr.put("t", "hdr");
        hdr.put("v", 1);
        hdr.put("rev", client.getRevision());
        hdr.put("world", world);
        hdr.put("rsn", local == null ? null : sanitizedName(local));
        hdr.put("at", now);
        hdr.put("cyc", lastCycle);
        writer.write(hdr);
        writePitch(client);
    }

    private void writePitch(Client client)
    {
        final WorldView wv = client.getTopLevelWorldView();
        final int plane = wv.getPlane();
        final int[][][] chunks = wv.getInstanceTemplateChunks();
        final int[][][] heights = wv.getTileHeights();
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", "pitch");
        line.put("cyc", lastCycle);
        line.put("plane", plane);
        line.put("baseX", wv.getBaseX());
        line.put("baseY", wv.getBaseY());
        line.put("chunks", chunks == null ? null : chunks[plane]);
        line.put("heights", heights == null ? null : scene(heights[plane]));
        writer.write(line);
    }

    /** The 104x104 scene part of a plane's tile heights (the client keeps one extra edge row). */
    private static int[][] scene(int[][] plane)
    {
        final int w = Math.min(SCENE, plane.length);
        final int[][] out = new int[w][];
        for (int x = 0; x < w; x++)
        {
            out[x] = Arrays.copyOf(plane[x], Math.min(SCENE, plane[x].length));
        }
        return out;
    }

    private void writeAll(List<Map<String, Object>> lines)
    {
        for (final Map<String, Object> line : lines)
        {
            writer.write(line);
        }
    }

    /** Same sanitizing as {@link ContactDetector}, so names match the spawn lines and contacts. */
    private static String sanitizedName(Player player)
    {
        final String name = player.getName();
        return name == null ? null : Text.sanitize(name);
    }
}
