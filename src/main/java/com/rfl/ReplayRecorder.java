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
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GroundObject;
import net.runelite.api.IterableHashTable;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Point;
import net.runelite.api.Projectile;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLite;
import net.runelite.client.util.Text;

/**
 * Records each player-owned house visit to {@code RUNELITE_DIR/rfl/replays/<fileName>}, a gzipped
 * NDJSON file the RFL replay viewer reads, while Record replays is on. Nothing is sent anywhere.
 *
 * <p>Each ClientTick it reads every player's pose, spot anims and every handegg projectile into a
 * {@link ReplaySampler}; each GameTick it adds a {@code tick} line, appearances and true tiles; finished
 * collisions and incompletes arrive through {@link #onEvent} (the {@link CollisionLog} listener),
 * and the local user's own plugin list ({@code plugins} at open) and plugin toggles through
 * {@link #onPlugins}.
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
    /**
     * Chebyshev radius, in tiles around the recorder, of the objects in {@code pitch.objs} /
     * {@code objs2} and of the non-zero cells of {@code under}, {@code over}, {@code shapes} and
     * {@code rots}.
     */
    private static final int OBJECT_RADIUS = 20;

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
    /** Set by {@link #shutdown}: the client is exiting, so no new file may open. */
    private boolean shutDown;
    private long frameNanos;
    private long frames;
    /** The local user's own plugin list for the {@code plugins} line; may be null or return null. */
    private Supplier<List<PluginEntry>> pluginSource;

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

    /** Client thread: whether a replay file is open and being written (false after a disk failure). */
    boolean recording()
    {
        return sampler != null && writer.isOpen();
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
        if (shutDown)
        {
            return;
        }
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
                player.getPoseAnimationFrame(), spots(player)));
        }
        final List<ReplaySampler.Ball> balls = new ArrayList<>();
        for (final Projectile p : client.getProjectiles())
        {
            if (IncompleteDetector.HANDEGG_PROJECTILES.contains(p.getId()))
            {
                balls.add(new ReplaySampler.Ball(p.getId(), p.getStartCycle(), p.getX(), p.getY(), p.getZ(),
                    p.getOrientation()));
            }
        }
        writeAll(sampler.frame(lastCycle, players, balls));
        frameNanos += System.nanoTime() - start;
        frames++;
    }

    /** Client thread, every GameTick: a {@code tick} line, changed appearances and changed true tiles. */
    void onGameTick(Client client)
    {
        if (sampler == null)
        {
            return;
        }
        final WorldView wv = client.getTopLevelWorldView();
        final List<ReplaySampler.Appearance> appearances = new ArrayList<>();
        final List<ReplaySampler.TrueTile> trueTiles = new ArrayList<>();
        for (final Player player : wv.players())
        {
            final String name = player == null ? null : sanitizedName(player);
            if (name == null)
            {
                continue;
            }
            final PlayerComposition comp = player.getPlayerComposition();
            if (comp != null)
            {
                appearances.add(new ReplaySampler.Appearance(name, comp.getGender(), comp.getEquipmentIds(),
                    comp.getColors()));
            }
            // The true tile's centre in local units, so it compares directly with the f rows' x/y.
            final WorldPoint world = player.getWorldLocation();
            final LocalPoint tile = world == null ? null : LocalPoint.fromWorld(wv, world);
            if (tile != null)
            {
                trueTiles.add(new ReplaySampler.TrueTile(name, tile.getX(), tile.getY()));
            }
        }
        writeAll(sampler.tick(client.getGameCycle(), client.getTickCount(), appearances, trueTiles));
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

    /** Client thread (the {@link CollisionLog} listener): one finished Collision or Incomplete. */
    void onEvent(Object collisionOrIncomplete)
    {
        if (sampler == null || collisionOrIncomplete == null)
        {
            return;
        }
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", "ev");
        line.put("cyc", lastCycle);
        line.put("e", collisionOrIncomplete);
        writer.write(line);
    }

    /**
     * Client thread: supplies the local user's own plugin list for the {@code plugins} line written
     * at file open, or null for none (Log plugin stats off). Null clears it.
     */
    void setPluginSource(Supplier<List<PluginEntry>> source)
    {
        this.pluginSource = source;
    }

    /**
     * Client thread: writes one plugin line ({@link #pluginsLine} or {@link #pluginToggleLine})
     * to the open file, beside the other line types. Nothing happens when no file is open.
     */
    void onPlugins(Object line)
    {
        if (sampler == null || line == null)
        {
            return;
        }
        writer.write(line);
    }

    /** Latest game cycle seen, for stamping plugin lines. */
    int cycle()
    {
        return lastCycle;
    }

    /** {@code {"t":"plugins","cyc":..,"list":[{"name":..,"enabled":..,"source":..}]}} */
    static Map<String, Object> pluginsLine(int cycle, List<PluginEntry> list)
    {
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", "plugins");
        line.put("cyc", cycle);
        line.put("list", list);
        return line;
    }

    /** {@code {"t":"plugin_toggle","cyc":..,"name":..,"enabled":..}} */
    static Map<String, Object> pluginToggleLine(int cycle, String name, boolean enabled)
    {
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", "plugin_toggle");
        line.put("cyc", cycle);
        line.put("name", name);
        line.put("enabled", enabled);
        return line;
    }

    /**
     * Client thread, on client exit: closes the open file and never opens another. The client
     * keeps ticking for a moment after ClientShutdown, and a file opened then would be cut off.
     */
    CompletableFuture<Void> shutdown()
    {
        shutDown = true;
        return stop();
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
        final Supplier<List<PluginEntry>> source = pluginSource;
        final List<PluginEntry> plugins = source == null ? null : source.get();
        if (plugins != null)
        {
            writer.write(pluginsLine(lastCycle, plugins));
        }
    }

    /**
     * Client thread, read only: builds the {@code pitch} line (spec §2.1) and hands it to the
     * writer. Arrays are allocated here, once per pitch, never per frame. {@code under},
     * {@code over}, {@code shapes} and {@code rots} are 104x104 {@code [x][y]} grids for the
     * current plane, cropped to {@link #OBJECT_RADIUS} around the recorder (0 outside, and all 0
     * when the recorder's tile is unknown).
     */
    private void writePitch(Client client)
    {
        final WorldView wv = client.getTopLevelWorldView();
        final int plane = wv.getPlane();
        final int[][][] chunks = wv.getInstanceTemplateChunks();
        final int[][][] heights = wv.getTileHeights();
        final Scene scene = wv.getScene();
        final Player local = client.getLocalPlayer();
        final LocalPoint at = local == null ? null : local.getLocalLocation();
        // Unknown recorder tile: an off-scene centre makes every crop come out empty.
        final int cx = at == null ? -OBJECT_RADIUS - 1 : at.getSceneX();
        final int cy = at == null ? -OBJECT_RADIUS - 1 : at.getSceneY();
        final short[][][] under = scene == null ? null : scene.getUnderlayIds();
        final short[][][] over = scene == null ? null : scene.getOverlayIds();
        final byte[][][] shapes = scene == null ? null : scene.getTileShapes();
        final ReplaySampler.PitchObjects objs = objects(scene, plane, at);
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", "pitch");
        line.put("cyc", lastCycle);
        line.put("plane", plane);
        line.put("baseX", wv.getBaseX());
        line.put("baseY", wv.getBaseY());
        line.put("chunks", chunks == null ? null : chunks[plane]);
        line.put("heights", heights == null ? null : scene(heights[plane]));
        line.put("objs", objs.rows());
        line.put("objs2", objs.rows2());
        line.put("under", ReplaySampler.PitchFloor.crop(planeOf(under, plane), cx, cy, OBJECT_RADIUS));
        line.put("over", ReplaySampler.PitchFloor.crop(planeOf(over, plane), cx, cy, OBJECT_RADIUS));
        line.put("shapes", ReplaySampler.PitchFloor.crop(planeOf(shapes, plane), cx, cy, OBJECT_RADIUS));
        line.put("rots", rotations(scene, plane, cx, cy));
        line.put("chunksAll", chunks);
        writer.write(line);
    }

    private static short[][] planeOf(short[][][] planes, int plane)
    {
        return planes == null || plane < 0 || plane >= planes.length ? null : planes[plane];
    }

    private static byte[][] planeOf(byte[][][] planes, int plane)
    {
        return planes == null || plane < 0 || plane >= planes.length ? null : planes[plane];
    }

    /**
     * Overlay rotation (0..3) per scene tile within {@link #OBJECT_RADIUS}. Scene has no rotation
     * array, so this reads {@link SceneTileModel#getRotation()} from each tile that has a shaped
     * tile model. Flat whole tiles (no model) and tiles outside the radius are 0.
     */
    private static int[][] rotations(Scene scene, int plane, int cx, int cy)
    {
        final int[][] out = new int[SCENE][SCENE];
        final Tile[][][] tiles = scene == null ? null : scene.getTiles();
        if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null)
        {
            return out;
        }
        final Tile[][] level = tiles[plane];
        for (int x = ReplaySampler.PitchObjects.lo(cx, OBJECT_RADIUS);
             x <= ReplaySampler.PitchObjects.hi(cx, OBJECT_RADIUS, Math.min(SCENE, level.length)); x++)
        {
            final Tile[] column = level[x];
            if (column == null)
            {
                continue;
            }
            for (int y = ReplaySampler.PitchObjects.lo(cy, OBJECT_RADIUS);
                 y <= ReplaySampler.PitchObjects.hi(cy, OBJECT_RADIUS, Math.min(SCENE, column.length)); y++)
            {
                final SceneTileModel model = column[y] == null ? null : column[y].getSceneTileModel();
                if (model != null)
                {
                    out[x][y] = model.getRotation();
                }
            }
        }
        return out;
    }

    /**
     * Every game, wall, ground and decorative object on {@code plane} on scene tiles within
     * {@link #OBJECT_RADIUS} (Chebyshev) of the recorder, as {@code objs} rows
     * {@code [id, type, orient, x, y]} and {@code objs2} rows
     * {@code [id, kind, config, x, y, sizeX, sizeY]}. A GameObject spanning several tiles is listed
     * once. Empty when the recorder's tile or the scene is unknown.
     */
    private static ReplaySampler.PitchObjects objects(Scene scene, int plane, LocalPoint at)
    {
        final ReplaySampler.PitchObjects objs = new ReplaySampler.PitchObjects();
        final Tile[][][] tiles = scene == null ? null : scene.getTiles();
        if (at == null || tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null)
        {
            return objs;
        }
        final Tile[][] level = tiles[plane];
        final int cx = at.getSceneX();
        final int cy = at.getSceneY();
        for (int x = ReplaySampler.PitchObjects.lo(cx, OBJECT_RADIUS);
             x <= ReplaySampler.PitchObjects.hi(cx, OBJECT_RADIUS, level.length); x++)
        {
            final Tile[] column = level[x];
            if (column == null)
            {
                continue;
            }
            for (int y = ReplaySampler.PitchObjects.lo(cy, OBJECT_RADIUS);
                 y <= ReplaySampler.PitchObjects.hi(cy, OBJECT_RADIUS, column.length); y++)
            {
                final Tile tile = column[y];
                if (tile != null)
                {
                    addObjects(objs, tile);
                }
            }
        }
        return objs;
    }

    private static void addObjects(ReplaySampler.PitchObjects objs, Tile tile)
    {
        final GameObject[] games = tile.getGameObjects();
        if (games != null)
        {
            for (final GameObject o : games)
            {
                final LocalPoint lp = o == null ? null : o.getLocalLocation();
                if (lp == null)
                {
                    continue;
                }
                final Point min = o.getSceneMinLocation();
                final Point max = o.getSceneMaxLocation();
                final boolean footprint = min != null && max != null;
                objs.add(ReplaySampler.PitchObjects.GAME, o.getHash(), o.getId(), o.getOrientation(),
                    lp.getX(), lp.getY(), o.getConfig(),
                    footprint ? ReplaySampler.PitchObjects.tileCentre(min.getX()) : lp.getX(),
                    footprint ? ReplaySampler.PitchObjects.tileCentre(min.getY()) : lp.getY(),
                    footprint ? ReplaySampler.PitchObjects.span(min.getX(), max.getX()) : 1,
                    footprint ? ReplaySampler.PitchObjects.span(min.getY(), max.getY()) : 1);
            }
        }
        final WallObject wall = tile.getWallObject();
        final LocalPoint wallAt = wall == null ? null : wall.getLocalLocation();
        if (wallAt != null)
        {
            objs.add(ReplaySampler.PitchObjects.WALL, wall.getHash(), wall.getId(), wall.getOrientationA(),
                wallAt.getX(), wallAt.getY(), wall.getConfig(), wallAt.getX(), wallAt.getY(), 1, 1);
        }
        final GroundObject ground = tile.getGroundObject();
        final LocalPoint groundAt = ground == null ? null : ground.getLocalLocation();
        if (groundAt != null)
        {
            objs.add(ReplaySampler.PitchObjects.GROUND, ground.getHash(), ground.getId(), 0,
                groundAt.getX(), groundAt.getY(), ground.getConfig(), groundAt.getX(), groundAt.getY(), 1, 1);
        }
        final DecorativeObject deco = tile.getDecorativeObject();
        final LocalPoint decoAt = deco == null ? null : deco.getLocalLocation();
        if (decoAt != null)
        {
            objs.add(ReplaySampler.PitchObjects.DECORATIVE, deco.getHash(), deco.getId(), 0,
                decoAt.getX(), decoAt.getY(), deco.getConfig(), decoAt.getX(), decoAt.getY(), 1, 1);
        }
    }

    /**
     * A player's spot anims as flat {@code (id, frame, height)} triples, or the shared
     * {@link ReplaySampler#NO_SPOTS} when there are none, so the common case allocates no array.
     */
    private static int[] spots(Player player)
    {
        final IterableHashTable<ActorSpotAnim> table = player.getSpotAnims();
        if (table == null)
        {
            return ReplaySampler.NO_SPOTS;
        }
        int n = 0;
        for (final ActorSpotAnim ignored : table)
        {
            n++;
        }
        if (n == 0)
        {
            return ReplaySampler.NO_SPOTS;
        }
        final int[] out = new int[n * 3];
        int k = 0;
        for (final ActorSpotAnim a : table)
        {
            if (k >= out.length)
            {
                break;
            }
            out[k++] = a.getId();
            out[k++] = a.getFrame();
            out[k++] = a.getHeight();
        }
        return k == out.length ? out : Arrays.copyOf(out, k);
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
