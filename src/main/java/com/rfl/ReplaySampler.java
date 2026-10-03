package com.rfl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Turns per-cycle player, ball and appearance state into the NDJSON line objects the replay
 * writer appends to disk (spec §2.2). Pure Java, no RuneLite dependency and no IO of its own,
 * so it can be unit tested without a client.
 *
 * <p>Caller passes already-sanitized names. Each returned line is a {@link LinkedHashMap} with
 * {@code "t"} inserted first, so Gson preserves the key order the line shapes specify.
 *
 * <p>State kept across calls: a stable name-&gt;index assignment (first-seen order, never
 * reused or shrunk), each player's last <em>written</em> {@code f} tuple, the set of names
 * present on the previous {@link #frame} call, the set of names {@link #frame} has currently
 * spawned, and each player's last appearance hash. A despawned player's last-written tuple is
 * forgotten, so a later respawn always gets a fresh full row in {@code f} even if their pose
 * happens to match what was last sent.
 *
 * <p>{@link #tick} only emits {@code app} for names {@link #frame} has spawned — an appearance
 * for a name nobody has spawned yet is dropped, and dropping it never assigns that name an
 * index. Spawning a name (first time, or a respawn after a despawn) clears its stored
 * appearance hash, so the next {@link #tick} always writes a fresh {@code app} for it, even if
 * the appearance happens to match what was last sent.
 *
 * <p>The same spawn gating and despawn reset apply to true tiles ({@code tt}, per GameTick) and
 * spot anims ({@code spot}, per ClientTick): a despawn forgets the last written value, so a
 * respawn writes it again. A player with no stored spot anims counts as having none, so a spawn
 * with no graphics writes no {@code spot} line.
 */
final class ReplaySampler
{
    /** One player's pose this cycle. */
    static final class PlayerState
    {
        final String name;
        final int x;
        final int y;
        final int orient;
        final int anim;
        final int animFrame;
        final int pose;
        final int poseFrame;

        /**
         * Spot anims as flat {@code (id, frame, height)} triples in any order; {@link #NO_SPOTS}
         * when there are none. The sampler sorts the triples in place.
         */
        final int[] spots;

        /**
         * Captures this player's current client model ({@code Player#getModel()}), or null when
         * there is none. Called on the client thread, only when the player's model key is new.
         * May be null itself (no model capture, for example in tests of other line types).
         */
        final Supplier<ModelCapture.Geometry> model;

        PlayerState(String name, int x, int y, int orient, int anim, int animFrame, int pose, int poseFrame)
        {
            this(name, x, y, orient, anim, animFrame, pose, poseFrame, NO_SPOTS);
        }

        PlayerState(String name, int x, int y, int orient, int anim, int animFrame, int pose, int poseFrame,
            int[] spots)
        {
            this(name, x, y, orient, anim, animFrame, pose, poseFrame, spots, null);
        }

        PlayerState(String name, int x, int y, int orient, int anim, int animFrame, int pose, int poseFrame,
            int[] spots, Supplier<ModelCapture.Geometry> model)
        {
            this.model = model;
            this.name = name;
            this.x = x;
            this.y = y;
            this.orient = orient;
            this.anim = anim;
            this.animFrame = animFrame;
            this.pose = pose;
            this.poseFrame = poseFrame;
            this.spots = spots == null ? NO_SPOTS : spots;
        }

        private int[] tuple(int i)
        {
            return new int[] { i, x, y, orient, anim, animFrame, pose, poseFrame };
        }
    }

    /** One player's appearance this game tick. */
    static final class Appearance
    {
        final String name;
        final int gender;
        final int[] equipment;
        final int[] colors;

        Appearance(String name, int gender, int[] equipment, int[] colors)
        {
            this.name = name;
            this.gender = gender;
            this.equipment = equipment;
            this.colors = colors;
        }

        private int hash()
        {
            return Objects.hash(gender, Arrays.hashCode(equipment), Arrays.hashCode(colors));
        }
    }

    /** Where the server says one player is this game tick: the local x/y of the true tile's centre. */
    static final class TrueTile
    {
        final String name;
        final int x;
        final int y;

        TrueTile(String name, int x, int y)
        {
            this.name = name;
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Collects the {@code pitch} line's {@code objs} rows {@code [id, type, orient, x, y]} and the
     * matching {@code objs2} rows {@code [id, kind, config, x, y, sizeX, sizeY]}, listing each
     * object once. A GameObject spanning several tiles is offered once per tile with the same
     * hash; {@link #add} keeps the first. {@link #lo} / {@link #hi} bound the square of scene tiles
     * within a Chebyshev radius, which is the recorder's distance filter.
     *
     * <p>{@code config} is the object's raw scene config: {@link #shape} is {@code config & 31}
     * (roof shapes 12..21 included), {@link #rotation} is {@code (config >>> 6) & 3}.
     */
    static final class PitchObjects
    {
        static final int GAME = 0;
        static final int WALL = 1;
        static final int GROUND = 2;
        static final int DECORATIVE = 3;

        private final List<Set<Long>> seen = List.of(new HashSet<>(), new HashSet<>(), new HashSet<>(),
            new HashSet<>());
        private final List<int[]> rows = new ArrayList<>();
        private final List<int[]> rows2 = new ArrayList<>();

        /** Lowest scene index within {@code radius} of {@code centre}, clipped to 0. */
        static int lo(int centre, int radius)
        {
            return Math.max(0, centre - radius);
        }

        /** Highest scene index within {@code radius} of {@code centre}, clipped to {@code size - 1}. */
        static int hi(int centre, int radius, int size)
        {
            return Math.min(size - 1, centre + radius);
        }

        /** Object shape from a scene config: 0..3 walls, 4..8 wall decorations, 10/11 game, 12..21 roofs, 22 ground. */
        static int shape(int config)
        {
            return config & 31;
        }

        /** Object rotation (0..3, quarter turns) from a scene config. */
        static int rotation(int config)
        {
            return (config >>> 6) & 3;
        }

        /** Local coordinate of the centre of scene tile {@code index} (128 local units per tile). */
        static int tileCentre(int index)
        {
            return index * 128 + 64;
        }

        /** Tiles spanned from {@code min} to {@code max} inclusive. */
        static int span(int min, int max)
        {
            return max - min + 1;
        }

        /**
         * Adds one object with only the {@code objs} fields: config 0, footprint 1x1 at the same
         * local point. False (and nothing added) when this type and hash were already added.
         */
        boolean add(int type, long hash, int id, int orient, int x, int y)
        {
            return add(type, hash, id, orient, x, y, 0, x, y, 1, 1);
        }

        /**
         * Adds one object to both {@code objs} ({@code x}/{@code y}: its local location) and
         * {@code objs2} ({@code x2}/{@code y2}: for a GameObject the south-west tile centre, else
         * the local location). False (and nothing added) when this type and hash were already added.
         */
        boolean add(int type, long hash, int id, int orient, int x, int y, int config, int x2, int y2,
            int sizeX, int sizeY)
        {
            if (!seen.get(type).add(hash))
            {
                return false;
            }
            rows.add(new int[] { id, type, orient, x, y });
            rows2.add(new int[] { id, type, config, x2, y2, sizeX, sizeY });
            return true;
        }

        List<int[]> rows()
        {
            return rows;
        }

        List<int[]> rows2()
        {
            return rows2;
        }
    }

    /**
     * Crops one plane of the scene floor arrays ({@code under}, {@code over}, {@code shapes}) to
     * a 104x104 {@code [x][y]} int grid holding values only within a Chebyshev radius of the
     * recorder's scene tile, 0 elsewhere. Source planes may be scene-sized (104, or 105 with an
     * edge row) or extended-scene-sized (184+), in which case scene tile 0 sits at index 40.
     */
    static final class PitchFloor
    {
        static final int SCENE = 104;
        static final int EXTENDED_SCENE = 184;

        private PitchFloor()
        {
        }

        /** Index of scene tile 0 in a source plane of {@code length} rows. */
        static int offset(int length)
        {
            return length >= EXTENDED_SCENE ? (EXTENDED_SCENE - SCENE) / 2 : 0;
        }

        /** Unsigned crop of a {@code short} plane (underlay / overlay ids, stored as id + 1). */
        static int[][] crop(short[][] plane, int cx, int cy, int radius)
        {
            final int[][] out = new int[SCENE][SCENE];
            if (plane == null)
            {
                return out;
            }
            final int off = offset(plane.length);
            for (int x = PitchObjects.lo(cx, radius); x <= PitchObjects.hi(cx, radius, SCENE); x++)
            {
                final short[] col = x + off < plane.length ? plane[x + off] : null;
                if (col == null)
                {
                    continue;
                }
                for (int y = PitchObjects.lo(cy, radius); y <= PitchObjects.hi(cy, radius, SCENE); y++)
                {
                    if (y + off < col.length)
                    {
                        out[x][y] = col[y + off] & 0xFFFF;
                    }
                }
            }
            return out;
        }

        /**
         * The {@code pitch.paint} array: an r, g, b triple per scene tile, tile {@code (x, y)} at
         * {@code (x * 104 + y) * 3}, from a 104x104 {@code [x][y]} grid of {@code 0xRRGGBB}.
         * Tiles outside the radius are cropped to 0, 0, 0 like {@code under}/{@code over}; 0, 0, 0
         * also means no paint.
         */
        static int[] paint(int[][] rgb, int cx, int cy, int radius)
        {
            final int[] out = new int[SCENE * SCENE * 3];
            if (rgb == null)
            {
                return out;
            }
            for (int x = PitchObjects.lo(cx, radius); x <= PitchObjects.hi(cx, radius, Math.min(SCENE, rgb.length));
                 x++)
            {
                final int[] col = rgb[x];
                if (col == null)
                {
                    continue;
                }
                for (int y = PitchObjects.lo(cy, radius); y <= PitchObjects.hi(cy, radius, Math.min(SCENE, col.length));
                     y++)
                {
                    final int at = (x * SCENE + y) * 3;
                    out[at] = (col[y] >> 16) & 0xff;
                    out[at + 1] = (col[y] >> 8) & 0xff;
                    out[at + 2] = col[y] & 0xff;
                }
            }
            return out;
        }

        /** Unsigned crop of a {@code byte} plane (tile shapes). */
        static int[][] crop(byte[][] plane, int cx, int cy, int radius)
        {
            final int[][] out = new int[SCENE][SCENE];
            if (plane == null)
            {
                return out;
            }
            final int off = offset(plane.length);
            for (int x = PitchObjects.lo(cx, radius); x <= PitchObjects.hi(cx, radius, SCENE); x++)
            {
                final byte[] col = x + off < plane.length ? plane[x + off] : null;
                if (col == null)
                {
                    continue;
                }
                for (int y = PitchObjects.lo(cy, radius); y <= PitchObjects.hi(cy, radius, SCENE); y++)
                {
                    if (y + off < col.length)
                    {
                        out[x][y] = col[y + off] & 0xFF;
                    }
                }
            }
            return out;
        }
    }

    /**
     * One house object renderable at pitch time, for {@code pitch.locs}. {@code part} is 0 for an
     * object's (first) renderable and 1 for a wall's or decoration's second one, which is a
     * different model under the same loc id, shape and rotation.
     */
    static final class Loc
    {
        final int id;
        final int config;
        final int part;
        final int x;
        final int y;
        final int height;
        /** Captures the renderable's model, or null; called only for a new key. May be null. */
        final Supplier<ModelCapture.Geometry> model;

        Loc(int id, int config, int part, int x, int y, int height, Supplier<ModelCapture.Geometry> model)
        {
            this.id = id;
            this.config = config;
            this.part = part;
            this.x = x;
            this.y = y;
            this.height = height;
            this.model = model;
        }

        private String key()
        {
            final String key = "l:" + id + ":" + PitchObjects.shape(config) + ":" + PitchObjects.rotation(config);
            return part == 0 ? key : key + ":" + part;
        }
    }

    /** {@link #pitchLocs} result: model lines to write before the pitch, its locs rows, and skips. */
    static final class PitchLocs
    {
        private final List<Map<String, Object>> lines;
        private final List<int[]> rows;
        private final int skipped;

        PitchLocs(List<Map<String, Object>> lines, List<int[]> rows, int skipped)
        {
            this.lines = lines;
            this.rows = rows;
            this.skipped = skipped;
        }

        /** {@code model} lines for loc keys first seen in this pitch; write them before the pitch. */
        List<Map<String, Object>> lines()
        {
            return lines;
        }

        /** {@code pitch.locs} rows {@code [modelId, localX, localY, groundHeight]}. */
        List<int[]> rows()
        {
            return rows;
        }

        /** Objects left out because they had no renderable or no model. */
        int skipped()
        {
            return skipped;
        }
    }

    /** One handegg projectile this cycle. */
    static final class Ball
    {
        final int id;
        final int startCycle;
        final double x;
        final double y;
        final double z;
        final int orient;
        /** Captures the projectile's client model, or null; called only for a new key. May be null. */
        final Supplier<ModelCapture.Geometry> model;

        Ball(int id, int startCycle, double x, double y, double z, int orient)
        {
            this(id, startCycle, x, y, z, orient, null);
        }

        Ball(int id, int startCycle, double x, double y, double z, int orient, Supplier<ModelCapture.Geometry> model)
        {
            this.model = model;
            this.id = id;
            this.startCycle = startCycle;
            this.x = x;
            this.y = y;
            this.z = z;
            this.orient = orient;
        }
    }

    private final Map<String, Integer> indices = new LinkedHashMap<>();
    private final Map<String, int[]> lastWritten = new LinkedHashMap<>();
    private final Map<String, Integer> lastAppearanceHash = new LinkedHashMap<>();
    /** Last written true tile per name, as {@code [x, y]}. */
    private final Map<String, int[]> lastTrueTile = new LinkedHashMap<>();
    /** Last written spot anims per name, sorted flat triples; absent means none. */
    private final Map<String, int[]> lastSpots = new LinkedHashMap<>();
    private Set<String> present = new LinkedHashSet<>();
    /** Names {@link #frame} has currently spawned; only its own spawn emission adds to this. */
    private final Set<String> spawned = new LinkedHashSet<>();

    /** Shared empty spot anim set, so a player with none costs no allocation. */
    static final int[] NO_SPOTS = new int[0];

    static final String PLAYER = "player";
    static final String BALL = "ball";
    static final String LOC = "loc";

    /** Model keys to ids; captures each key's first model (spec §2.3). */
    private final ModelCapture.Registry models = new ModelCapture.Registry();
    /** Kind of each id captured but not yet turned into a model line. */
    private final Map<Integer, String> pendingKind = new LinkedHashMap<>();
    /** Appearance hash of each player id captured but not yet turned into a model line. */
    private final Map<Integer, Integer> pendingAppearance = new LinkedHashMap<>();
    /** First full player model per appearance hash: the base later poses are delta-encoded against. */
    private final Map<Integer, ModelCapture.Captured> appearanceBase = new LinkedHashMap<>();
    /**
     * Latest appearance hash per name from {@link #tick}, for player model keys. Unlike
     * {@link #lastAppearanceHash} it is not cleared on spawn or despawn,
     * so a returning player's model key is ready at once; every tick refreshes it.
     */
    private final Map<String, Integer> modelAppearance = new LinkedHashMap<>();
    /** Last model id written in a {@code pm} row per name; cleared on despawn. */
    private final Map<String, Integer> lastPm = new LinkedHashMap<>();
    private long lastModelNanos;
    private boolean lastFrameNewModels;

    /** Distinct model keys captured so far. */
    int modelsCaptured()
    {
        return models.size();
    }

    /** Nanoseconds the last {@link #frame} spent resolving model keys (capture included). */
    long lastModelNanos()
    {
        return lastModelNanos;
    }

    /** Whether the last {@link #frame} captured at least one new model. */
    boolean lastFrameNewModels()
    {
        return lastFrameNewModels;
    }

    /**
     * Lines for one ClientTick: model lines for keys first seen this cycle, then despawn, spawn,
     * f, pm, spot, then ball lines, in that order. Model lines come first so each precedes the
     * {@code pm} / {@code ball} lines that refer to its id.
     */
    List<Map<String, Object>> frame(int cycle, List<PlayerState> players, List<Ball> balls)
    {
        List<Map<String, Object>> lines = new ArrayList<>();

        final long modelStart = System.nanoTime();
        final int before = models.size();
        final int[] playerModels = new int[players.size()];
        for (int k = 0; k < playerModels.length; k++)
        {
            playerModels[k] = playerModelId(players.get(k));
        }
        final int[] ballModels = new int[balls.size()];
        for (int k = 0; k < ballModels.length; k++)
        {
            final Ball b = balls.get(k);
            ballModels[k] = cycle <= b.startCycle ? -1 : modelId("b:" + b.id, BALL, b.model, 0);
        }
        lastFrameNewModels = models.size() != before;
        if (lastFrameNewModels)
        {
            lines.addAll(modelLines());
        }
        lastModelNanos = System.nanoTime() - modelStart;

        Set<String> now = new LinkedHashSet<>();
        for (PlayerState p : players)
        {
            now.add(p.name);
        }

        for (String name : present)
        {
            if (!now.contains(name))
            {
                lines.add(despawnLine(cycle, indexOf(name)));
                // Forget the last tuple so a later respawn always writes a fresh full row,
                // even if the pose on return happens to match what was last sent. Also
                // un-spawn the name so tick() stops emitting app for it until it respawns.
                lastWritten.remove(name);
                spawned.remove(name);
                lastTrueTile.remove(name);
                lastSpots.remove(name);
                lastPm.remove(name);
            }
        }

        for (PlayerState p : players)
        {
            if (!present.contains(p.name))
            {
                lines.add(spawnLine(cycle, indexFor(p.name), p.name));
                spawned.add(p.name);
                // Forget the last appearance hash so the next tick() always writes a fresh
                // app for this name, even if the appearance happens to match what was last sent.
                lastAppearanceHash.remove(p.name);
            }
        }

        List<int[]> rows = new ArrayList<>();
        for (PlayerState p : players)
        {
            int[] tuple = p.tuple(indexFor(p.name));
            int[] last = lastWritten.get(p.name);
            if (last == null || !Arrays.equals(last, tuple))
            {
                rows.add(tuple);
                lastWritten.put(p.name, tuple);
            }
        }
        if (!rows.isEmpty())
        {
            lines.add(fLine(cycle, rows));
        }

        List<int[]> pmRows = new ArrayList<>();
        for (int k = 0; k < playerModels.length; k++)
        {
            final int id = playerModels[k];
            if (id < 0)
            {
                continue;
            }
            final String name = players.get(k).name;
            final Integer last = lastPm.get(name);
            if (last == null || last != id)
            {
                pmRows.add(new int[] { indexFor(name), id });
                lastPm.put(name, id);
            }
        }
        if (!pmRows.isEmpty())
        {
            lines.add(pmLine(cycle, pmRows));
        }

        for (PlayerState p : players)
        {
            sortSpots(p.spots);
            int[] last = lastSpots.get(p.name);
            boolean changed = last == null ? p.spots.length > 0 : !Arrays.equals(last, p.spots);
            if (changed)
            {
                lines.add(spotLine(cycle, indexFor(p.name), p.spots));
                if (p.spots.length == 0)
                {
                    lastSpots.remove(p.name);
                }
                else
                {
                    lastSpots.put(p.name, p.spots);
                }
            }
        }

        for (int k = 0; k < ballModels.length; k++)
        {
            final Ball b = balls.get(k);
            // The client creates a projectile ~0.8 s early, parked at (0, 0) through its start
            // cycle; it has a real position only from the cycle after.
            if (cycle <= b.startCycle)
            {
                continue;
            }
            lines.add(ballLine(cycle, b, ballModels[k]));
        }

        present = now;
        return lines;
    }

    /** {@link #tick(int, int, List, List)} with no true tiles. */
    List<Map<String, Object>> tick(int cycle, int tick, List<Appearance> appearances)
    {
        return tick(cycle, tick, appearances, List.of());
    }

    /**
     * Lines for one GameTick: tick, then app lines for spawned players whose appearance
     * changed, then one tt line for spawned players whose true tile changed (none when nothing
     * did). An appearance or true tile for a name {@link #frame} hasn't spawned (yet, or anymore)
     * is skipped and never assigned an index.
     */
    List<Map<String, Object>> tick(int cycle, int tick, List<Appearance> appearances, List<TrueTile> trueTiles)
    {
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(tickLine(cycle, tick));

        for (Appearance a : appearances)
        {
            int hash = a.hash();
            // Kept for every name, spawned or not, so a player's model key is ready by their spawn.
            modelAppearance.put(a.name, hash);
            if (!spawned.contains(a.name))
            {
                continue;
            }
            Integer lastHash = lastAppearanceHash.get(a.name);
            if (lastHash == null || lastHash != hash)
            {
                lines.add(appLine(cycle, indexOf(a.name), a));
                lastAppearanceHash.put(a.name, hash);
            }
        }

        List<int[]> rows = new ArrayList<>();
        for (TrueTile tt : trueTiles)
        {
            if (!spawned.contains(tt.name))
            {
                continue;
            }
            int[] last = lastTrueTile.get(tt.name);
            if (last == null || last[0] != tt.x || last[1] != tt.y)
            {
                rows.add(new int[] { indexOf(tt.name), tt.x, tt.y });
                lastTrueTile.put(tt.name, new int[] { tt.x, tt.y });
            }
        }
        if (!rows.isEmpty())
        {
            lines.add(ttLine(cycle, rows));
        }

        return lines;
    }

    /**
     * Sorts flat {@code (id, frame, height)} triples in place, so the same set compares equal
     * whatever order the client's hash table iterates in. Insertion sort: a player carries a
     * handful of spot anims at most.
     */
    static void sortSpots(int[] flat)
    {
        for (int i = 3; i + 2 < flat.length; i += 3)
        {
            for (int j = i; j >= 3 && compareTriple(flat, j - 3, j) > 0; j -= 3)
            {
                for (int k = 0; k < 3; k++)
                {
                    int t = flat[j - 3 + k];
                    flat[j - 3 + k] = flat[j + k];
                    flat[j + k] = t;
                }
            }
        }
    }

    private static int compareTriple(int[] flat, int a, int b)
    {
        for (int k = 0; k < 3; k++)
        {
            int c = Integer.compare(flat[a + k], flat[b + k]);
            if (c != 0)
            {
                return c;
            }
        }
        return 0;
    }

    /** Index assigned to a name, or -1 if never seen. */
    int indexOf(String name)
    {
        Integer i = indices.get(name);
        return i == null ? -1 : i;
    }

    /** Index for a name, assigning the next one in first-seen order if this is new. */
    private int indexFor(String name)
    {
        Integer i = indices.get(name);
        if (i == null)
        {
            i = indices.size();
            indices.put(name, i);
        }
        return i;
    }

    private static Map<String, Object> despawnLine(int cycle, int i)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "despawn");
        m.put("cyc", cycle);
        m.put("i", i);
        return m;
    }

    private static Map<String, Object> spawnLine(int cycle, int i, String name)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "spawn");
        m.put("cyc", cycle);
        m.put("i", i);
        m.put("name", name);
        return m;
    }

    private static Map<String, Object> fLine(int cycle, List<int[]> rows)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "f");
        m.put("cyc", cycle);
        m.put("p", rows);
        return m;
    }

    private static Map<String, Object> spotLine(int cycle, int i, int[] spots)
    {
        List<int[]> s = new ArrayList<>(spots.length / 3);
        for (int k = 0; k + 2 < spots.length; k += 3)
        {
            s.add(new int[] { spots[k], spots[k + 1], spots[k + 2] });
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "spot");
        m.put("cyc", cycle);
        m.put("i", i);
        m.put("s", s);
        return m;
    }

    private static Map<String, Object> ttLine(int cycle, List<int[]> rows)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "tt");
        m.put("cyc", cycle);
        m.put("p", rows);
        return m;
    }

    private static Map<String, Object> ballLine(int cycle, Ball b, int modelId)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "ball");
        m.put("cyc", cycle);
        m.put("id", b.id);
        m.put("sc", b.startCycle);
        m.put("x", b.x);
        m.put("y", b.y);
        m.put("z", b.z);
        m.put("o", b.orient);
        if (modelId >= 0)
        {
            m.put("m", modelId);
        }
        return m;
    }

    private static Map<String, Object> pmLine(int cycle, List<int[]> rows)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "pm");
        m.put("cyc", cycle);
        m.put("p", rows);
        return m;
    }

    /**
     * The model id for this player's current key, capturing on a new key; -1 when there is no
     * model yet. Waits for the appearance hash from {@link #tick}, and never captures while a spot
     * anim is active: the client merges spot anim models into {@code Player#getModel()}, and the
     * first model per key is kept forever. The player's previous {@code pm} value carries on.
     */
    private int playerModelId(PlayerState p)
    {
        if (p.model == null)
        {
            return -1;
        }
        final Integer appearance = modelAppearance.get(p.name);
        if (appearance == null)
        {
            return -1;
        }
        final String key = "p:" + appearance + ":" + p.anim + ":" + p.animFrame + ":" + p.pose + ":" + p.poseFrame;
        return modelId(key, PLAYER, p.spots.length > 0 ? null : p.model, appearance);
    }

    /**
     * {@link ModelCapture.Registry#idFor} that also notes a new id's kind (and, for a player, its
     * appearance) for {@link #modelLines}. A null {@code capture} only looks the key up.
     */
    private int modelId(String key, String kind, Supplier<ModelCapture.Geometry> capture, int appearance)
    {
        final int before = models.size();
        final int id = models.idFor(key, capture == null ? () -> null : capture);
        if (models.size() != before)
        {
            pendingKind.put(id, kind);
            if (PLAYER.equals(kind))
            {
                pendingAppearance.put(id, appearance);
            }
        }
        return id;
    }

    /**
     * Pitch-time house objects: one model per (loc id, shape, rotation[, part]) key, first model
     * kept, and a {@code locs} row per object that has one. Objects with no renderable or model
     * are skipped and counted.
     */
    PitchLocs pitchLocs(List<Loc> locs)
    {
        final List<int[]> rows = new ArrayList<>();
        int skipped = 0;
        for (final Loc loc : locs)
        {
            final int id = loc.model == null ? -1 : modelId(loc.key(), LOC, loc.model, 0);
            if (id < 0)
            {
                skipped++;
                continue;
            }
            rows.add(new int[] { id, loc.x, loc.y, loc.height });
        }
        return new PitchLocs(modelLines(), rows, skipped);
    }

    /**
     * {@code model} lines for every id captured since the last call, in id order. A player pose
     * whose appearance already has a base model with the same faces and colours is written as
     * {@code base} + {@code dv} (vertex deltas, same vertex order); anything else in full, and the
     * first full player model of an appearance becomes its base.
     */
    private List<Map<String, Object>> modelLines()
    {
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final ModelCapture.Captured c : models.takeNew())
        {
            final String kind = pendingKind.remove(c.id);
            final Integer appearance = pendingAppearance.remove(c.id);
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", "model");
            m.put("id", c.id);
            m.put("kind", kind);
            final ModelCapture.Captured base = appearance == null ? null : appearanceBase.get(appearance);
            if (base != null && sameTopology(base.geometry, c.geometry))
            {
                m.put("base", base.id);
                m.put("dv", deltas(base.geometry.vertices, c.geometry.vertices));
            }
            else
            {
                if (appearance != null && base == null)
                {
                    appearanceBase.put(appearance, c);
                }
                m.put("v", c.geometry.vertices);
                m.put("f", c.geometry.faces);
                m.put("c", c.geometry.colors);
            }
            out.add(m);
        }
        return out;
    }

    private static boolean sameTopology(ModelCapture.Geometry a, ModelCapture.Geometry b)
    {
        return a.vertices.length == b.vertices.length && Arrays.equals(a.faces, b.faces)
            && Arrays.equals(a.colors, b.colors);
    }

    private static int[] deltas(int[] base, int[] vertices)
    {
        final int[] d = new int[vertices.length];
        for (int k = 0; k < d.length; k++)
        {
            d[k] = vertices[k] - base[k];
        }
        return d;
    }

    private static Map<String, Object> tickLine(int cycle, int tick)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "tick");
        m.put("cyc", cycle);
        m.put("tick", tick);
        return m;
    }

    private static Map<String, Object> appLine(int cycle, int i, Appearance a)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "app");
        m.put("cyc", cycle);
        m.put("i", i);
        m.put("g", a.gender);
        m.put("eq", a.equipment);
        m.put("col", a.colors);
        return m;
    }
}
