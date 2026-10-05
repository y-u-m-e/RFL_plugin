package com.rfl.replay.pitch;

import com.rfl.replay.RecorderStats;
import com.rfl.replay.ReplaySampler;
import sh.yumekui.toolkit.model.ModelCapture;
import sh.yumekui.toolkit.model.RenderableModels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * Builds the {@code pitch} line (spec §2.1) from the loaded scene, one stage per ClientTick so no
 * frame pays for the whole scan, then hands it to {@link PitchCapture}, which reads its house models
 * over the following ClientTicks and writes it.
 *
 * <ol>
 * <li>Floor: the plane, heights and floor crops, and the scan window ({@link PitchWindow}: the
 * house's template chunks plus a tile, or {@link #OBJECT_RADIUS} around the recorder outside an
 * instance).</li>
 * <li>Objects: every object in the window, and a {@code pitch.locs} candidate per renderable.</li>
 * <li>Finish: the overlay rotations and paint, then {@link PitchCapture#begin}.</li>
 * </ol>
 *
 * <p>Every key is put in the first stage, so the line keeps the v1 key order. Arrays are allocated
 * once per pitch, never per frame. {@code under}, {@code over}, {@code shapes} and {@code rots} are
 * 104x104 {@code [x][y]} grids for the current plane, cropped to the window (0 outside it). A
 * LOADING restarts the scan ({@link #start}); a stop mid-scan writes no pitch.
 *
 * <p>Client thread only; it only reads the client.
 */
public final class PitchScanner
{
    private static final int SCENE = PitchWindow.SCENE;
    /**
     * Chebyshev radius, in tiles around the recorder, of the objects in {@code pitch.objs} /
     * {@code objs2} and of the non-zero cells of {@code under}, {@code over}, {@code shapes} and
     * {@code rots}, outside an instance.
     */
    private static final int OBJECT_RADIUS = 20;
    /** An off-scene centre for an unknown recorder tile, which makes the fallback window empty. */
    private static final int OFF_SCENE = -OBJECT_RADIUS - 1;
    /** Orientations run 0-2047 for a full turn, one {@code Perspective.SINE} entry each. */
    private static final int ORIENTATION_MASK = Perspective.SINE.length - 1;
    /** The 24 bits of an RGB colour, without the alpha byte the client may leave set. */
    private static final int RGB_MASK = 0xFFFFFF;

    /** Which stage the next {@link #step} runs. */
    private static final int IDLE = 0;
    private static final int READ_FLOOR = 1;
    private static final int READ_OBJECTS = 2;
    private static final int FINISH = 3;

    private final PitchCapture capture;
    private final RecorderStats stats;
    private final RenderableModels renderables = new RenderableModels();

    private int stage = IDLE;
    /** The line so far, and what the floor stage fixed for the later stages. */
    private Map<String, Object> line;
    private List<Loc> locs;
    private Scene scene;
    private int plane;
    private PitchWindow window;

    public PitchScanner(PitchCapture capture, RecorderStats stats)
    {
        this.capture = capture;
        this.stats = stats;
    }

    /**
     * Queues a fresh scan, one stage per ClientTick. A pitch still waiting on its house models is
     * given up on now, since the scene it describes is gone.
     */
    public void start()
    {
        capture.abandon();
        stage = READ_FLOOR;
        line = null;
        locs = null;
    }

    /** The file closed: forgets any scan in progress, so no pitch is written. */
    public void stop()
    {
        stage = IDLE;
        line = null;
        locs = null;
        scene = null;
        window = null;
    }

    /** Whether a scan is in progress. */
    public boolean scanning()
    {
        return stage != IDLE;
    }

    /** Runs the next stage; the last hands the line to {@link PitchCapture}. */
    public void step(Client client, int cycle, ReplaySampler sampler)
    {
        long start = System.nanoTime();
        switch (stage)
        {
            case READ_FLOOR:
                readFloor(client, cycle);
                stage = READ_OBJECTS;
                break;
            case READ_OBJECTS:
                locs = new ArrayList<>();
                PitchObjects objects = objects(locs);
                line.put("objs", objects.rows());
                line.put("objs2", objects.rows2());
                stage = FINISH;
                break;
            case FINISH:
                line.put("rots", rotations());
                line.put("paint", PitchFloor.paint(paint(), window));
                capture.begin(sampler, line, locs);
                stop();
                break;
            default:
                break;
        }
        stats.addPitchNanos(System.nanoTime() - start);
    }

    /** The first stage: the line with every key in place, the floor filled in. */
    private void readFloor(Client client, int cycle)
    {
        WorldView view = client.getTopLevelWorldView();
        plane = view.getPlane();
        // Copied: the line is serialised later, on the writer's thread.
        int[][][] chunks = copy(view.getInstanceTemplateChunks());
        int[][][] heights = view.getTileHeights();
        scene = view.getScene();
        Player local = client.getLocalPlayer();
        LocalPoint at = local == null ? null : local.getLocalLocation();
        int centreX = at == null ? OFF_SCENE : at.getSceneX();
        int centreY = at == null ? OFF_SCENE : at.getSceneY();
        // The whole house (its template chunks), not just the tiles around the recorder.
        window = PitchWindow.house(view.isInstance() ? chunks : null, centreX, centreY, OBJECT_RADIUS);
        short[][][] under = scene == null ? null : scene.getUnderlayIds();
        short[][][] over = scene == null ? null : scene.getOverlayIds();
        byte[][][] shapes = scene == null ? null : scene.getTileShapes();
        line = new LinkedHashMap<>();
        line.put("t", "pitch");
        line.put("cyc", cycle);
        line.put("plane", plane);
        line.put("baseX", view.getBaseX());
        line.put("baseY", view.getBaseY());
        line.put("chunks", chunks == null ? null : chunks[plane]);
        line.put("heights", heights == null ? null : sceneHeights(heights[plane]));
        // Filled in by the objects stage.
        line.put("objs", null);
        line.put("objs2", null);
        line.put("under", PitchFloor.crop(planeOf(under, plane), window));
        line.put("over", PitchFloor.crop(planeOf(over, plane), window));
        line.put("shapes", PitchFloor.crop(planeOf(shapes, plane), window));
        // Filled in by the finish stage.
        line.put("rots", null);
        line.put("chunksAll", chunks);
        // Filled in when PitchCapture writes the line.
        line.put("locs", null);
        line.put("paint", null);
    }

    /** The scanned plane's tiles, or null when the scene is unknown. */
    private Tile[][] level()
    {
        Tile[][][] tiles = scene == null ? null : scene.getTiles();
        return tiles == null || plane < 0 || plane >= tiles.length ? null : tiles[plane];
    }

    /**
     * Every game, wall, ground and decorative object on scene tiles in the window, as {@code objs}
     * rows {@code [id, type, orient, x, y]} and {@code objs2} rows
     * {@code [id, kind, config, x, y, sizeX, sizeY]}, each with its {@code pitch.locs} candidates
     * added to {@code locs}. A GameObject spanning several tiles is listed once. Empty when the
     * scene is unknown (or the window is, outside a house with no recorder tile).
     */
    private PitchObjects objects(List<Loc> locs)
    {
        PitchObjects objects = new PitchObjects();
        Tile[][] level = level();
        if (level == null)
        {
            return objects;
        }
        for (int x = window.x0; x <= Math.min(window.x1, level.length - 1); x++)
        {
            Tile[] column = level[x];
            for (int y = window.y0; column != null && y <= Math.min(window.y1, column.length - 1); y++)
            {
                Tile tile = column[y];
                if (tile != null)
                {
                    addGameObjects(objects, tile, locs);
                    addWall(objects, tile, locs);
                    addGround(objects, tile, locs);
                    addDecoration(objects, tile, locs);
                }
            }
        }
        return objects;
    }

    private void addGameObjects(PitchObjects objects, Tile tile, List<Loc> locs)
    {
        GameObject[] games = tile.getGameObjects();
        if (games == null)
        {
            return;
        }
        for (GameObject game : games)
        {
            LocalPoint at = game == null ? null : game.getLocalLocation();
            if (at == null)
            {
                continue;
            }
            // objs2 places a multi-tile object at its south-west tile, with its size in tiles.
            Point min = game.getSceneMinLocation();
            Point max = game.getSceneMaxLocation();
            boolean footprint = min != null && max != null;
            boolean added = objects.add(PitchObjects.GAME, game.getHash(), game.getId(), game.getOrientation(),
                at.getX(), at.getY(), game.getConfig(),
                footprint ? PitchObjects.tileCentre(min.getX()) : at.getX(),
                footprint ? PitchObjects.tileCentre(min.getY()) : at.getY(),
                footprint ? PitchObjects.span(min.getX(), max.getX()) : 1,
                footprint ? PitchObjects.span(min.getY(), max.getY()) : 1);
            if (added)
            {
                addLoc(locs, game.getId(), Loc.GAME, game.getConfig(), 0, game.getModelOrientation(), at.getX(),
                    at.getY(), game.getZ(), game.getRenderable());
            }
        }
    }

    /** A wall, and its second renderable (part 1) when it has one. */
    private void addWall(PitchObjects objects, Tile tile, List<Loc> locs)
    {
        WallObject wall = tile.getWallObject();
        LocalPoint at = wall == null ? null : wall.getLocalLocation();
        if (at == null || !objects.add(PitchObjects.WALL, wall.getHash(), wall.getId(), wall.getOrientationA(),
            at.getX(), at.getY(), wall.getConfig(), at.getX(), at.getY(), 1, 1))
        {
            return;
        }
        addLoc(locs, wall.getId(), Loc.WALL, wall.getConfig(), 0, 0, at.getX(), at.getY(), wall.getZ(),
            wall.getRenderable1());
        if (wall.getRenderable2() != null)
        {
            addLoc(locs, wall.getId(), Loc.WALL, wall.getConfig(), 1, 0, at.getX(), at.getY(), wall.getZ(),
                wall.getRenderable2());
        }
    }

    private void addGround(PitchObjects objects, Tile tile, List<Loc> locs)
    {
        GroundObject ground = tile.getGroundObject();
        LocalPoint at = ground == null ? null : ground.getLocalLocation();
        if (at != null && objects.add(PitchObjects.GROUND, ground.getHash(), ground.getId(), 0,
            at.getX(), at.getY(), ground.getConfig(), at.getX(), at.getY(), 1, 1))
        {
            addLoc(locs, ground.getId(), Loc.GROUND, ground.getConfig(), 0, 0, at.getX(), at.getY(), ground.getZ(),
                ground.getRenderable());
        }
    }

    /** A wall decoration, and its second renderable (part 1) when it has one. */
    private void addDecoration(PitchObjects objects, Tile tile, List<Loc> locs)
    {
        DecorativeObject decoration = tile.getDecorativeObject();
        LocalPoint at = decoration == null ? null : decoration.getLocalLocation();
        if (at == null || !objects.add(PitchObjects.DECORATIVE, decoration.getHash(), decoration.getId(), 0,
            at.getX(), at.getY(), decoration.getConfig(), at.getX(), at.getY(), 1, 1))
        {
            return;
        }
        // Wall decorations sit off the tile's local point by their own offsets.
        addLoc(locs, decoration.getId(), Loc.DECORATIVE, decoration.getConfig(), 0, 0,
            at.getX() + decoration.getXOffset(), at.getY() + decoration.getYOffset(), decoration.getZ(),
            decoration.getRenderable());
        if (decoration.getRenderable2() != null)
        {
            addLoc(locs, decoration.getId(), Loc.DECORATIVE, decoration.getConfig(), 1, 0,
                at.getX() + decoration.getXOffset2(), at.getY() + decoration.getYOffset2(), decoration.getZ(),
                decoration.getRenderable2());
        }
    }

    /**
     * One {@code pitch.locs} candidate. A null renderable gets a null capture, which the pass skips
     * and counts; the model itself is read only when the loc key is new.
     */
    private void addLoc(List<Loc> locs, int id, char kind, int config, int part, int orient, int x, int y,
        int height, Renderable renderable)
    {
        locs.add(Loc.withReasons(id, kind, config, part, orient, x, y, height,
            renderable == null ? null : () -> captureLoc(renderable, orient)));
    }

    /**
     * A house object's model as the client draws it ({@link RenderableModels}), copied into replay
     * geometry, or why there is none. A GameObject's model orientation is applied the way the GPU
     * plugin places it.
     */
    private LocModel captureLoc(Renderable renderable, int orient)
    {
        try
        {
            Model model = renderables.resolve(renderable);
            if (model == null)
            {
                stats.locRead(renderables.path(), false);
                return LocModel.skipped(LocSkip.NO_MODEL);
            }
            if (!RenderableModels.hasArrays(model))
            {
                stats.locRead(renderables.path(), false);
                return LocModel.skipped(LocSkip.NO_ARRAYS);
            }
            ModelCapture.Geometry geometry = RenderableModels.geometry(model);
            int turn = orient & ORIENTATION_MASK;
            if (turn != 0)
            {
                geometry = ModelCapture.rotateY(geometry, Perspective.SINE[turn], Perspective.COSINE[turn]);
            }
            stats.locRead(renderables.path(), true);
            return LocModel.of(geometry);
        }
        catch (RuntimeException e)
        {
            stats.captureFailed();
            stats.locRead(renderables.path(), false);
            return LocModel.skipped(LocSkip.THREW);
        }
    }

    /**
     * Overlay rotation (0..3) per scene tile in the window. Scene has no rotation array, so this reads
     * {@link SceneTileModel#getRotation()} from each tile with a shaped tile model. Flat whole tiles
     * (no model) and tiles outside the window are 0.
     */
    private int[][] rotations()
    {
        int[][] out = new int[SCENE][SCENE];
        Tile[][] level = level();
        if (level == null)
        {
            return out;
        }
        for (int x = window.x0; x <= Math.min(window.x1, level.length - 1); x++)
        {
            Tile[] column = level[x];
            for (int y = window.y0; column != null && y <= Math.min(window.y1, column.length - 1); y++)
            {
                SceneTileModel model = column[y] == null ? null : column[y].getSceneTileModel();
                if (model != null)
                {
                    out[x][y] = model.getRotation();
                }
            }
        }
        return out;
    }

    /**
     * Floor paint colour ({@code 0xRRGGBB}) per scene tile in the window: a flat tile's
     * {@link SceneTilePaint#getRBG()} (the client's own RGB for that tile), and for a shaped tile its
     * overlay colour ({@link SceneTileModel#getModelOverlay()}, underlay when there is no overlay).
     * 0 where a tile has neither.
     */
    private int[][] paint()
    {
        int[][] out = new int[SCENE][SCENE];
        Tile[][] level = level();
        if (level == null)
        {
            return out;
        }
        for (int x = window.x0; x <= Math.min(window.x1, level.length - 1); x++)
        {
            Tile[] column = level[x];
            for (int y = window.y0; column != null && y <= Math.min(window.y1, column.length - 1); y++)
            {
                if (column[y] != null)
                {
                    out[x][y] = tileColour(column[y]);
                }
            }
        }
        return out;
    }

    private static int tileColour(Tile tile)
    {
        SceneTilePaint flat = tile.getSceneTilePaint();
        if (flat != null)
        {
            return flat.getRBG() & RGB_MASK;
        }
        SceneTileModel shaped = tile.getSceneTileModel();
        if (shaped == null)
        {
            return 0;
        }
        int overlay = shaped.getModelOverlay() & RGB_MASK;
        return overlay != 0 ? overlay : shaped.getModelUnderlay() & RGB_MASK;
    }

    private static int[][][] copy(int[][][] planes)
    {
        if (planes == null)
        {
            return null;
        }
        int[][][] out = new int[planes.length][][];
        for (int p = 0; p < planes.length; p++)
        {
            if (planes[p] != null)
            {
                out[p] = new int[planes[p].length][];
                for (int x = 0; x < planes[p].length; x++)
                {
                    out[p][x] = planes[p][x] == null ? null : planes[p][x].clone();
                }
            }
        }
        return out;
    }

    private static short[][] planeOf(short[][][] planes, int plane)
    {
        return planes == null || plane < 0 || plane >= planes.length ? null : planes[plane];
    }

    private static byte[][] planeOf(byte[][][] planes, int plane)
    {
        return planes == null || plane < 0 || plane >= planes.length ? null : planes[plane];
    }

    /** The 104x104 scene part of a plane's tile heights (the client keeps one extra edge row). */
    private static int[][] sceneHeights(int[][] plane)
    {
        int width = Math.min(SCENE, plane.length);
        int[][] out = new int[width][];
        for (int x = 0; x < width; x++)
        {
            out[x] = Arrays.copyOf(plane[x], Math.min(SCENE, plane[x].length));
        }
        return out;
    }
}
