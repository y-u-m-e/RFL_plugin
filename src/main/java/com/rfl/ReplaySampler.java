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

        PlayerState(String name, int x, int y, int orient, int anim, int animFrame, int pose, int poseFrame)
        {
            this(name, x, y, orient, anim, animFrame, pose, poseFrame, NO_SPOTS);
        }

        PlayerState(String name, int x, int y, int orient, int anim, int animFrame, int pose, int poseFrame,
            int[] spots)
        {
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
     * Collects the {@code pitch} line's {@code objs} rows {@code [id, type, orient, x, y]}, listing
     * each object once. A GameObject spanning several tiles is offered once per tile with the same
     * hash; {@link #add} keeps the first. {@link #lo} / {@link #hi} bound the square of scene tiles
     * within a Chebyshev radius, which is the recorder's distance filter.
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

        /** Adds one object; false (and nothing added) when this type and hash were already added. */
        boolean add(int type, long hash, int id, int orient, int x, int y)
        {
            if (!seen.get(type).add(hash))
            {
                return false;
            }
            rows.add(new int[] { id, type, orient, x, y });
            return true;
        }

        List<int[]> rows()
        {
            return rows;
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

        Ball(int id, int startCycle, double x, double y, double z, int orient)
        {
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

    /** Lines for one ClientTick: despawn, spawn, f, spot, then ball lines, in that order. */
    List<Map<String, Object>> frame(int cycle, List<PlayerState> players, List<Ball> balls)
    {
        List<Map<String, Object>> lines = new ArrayList<>();

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

        for (Ball b : balls)
        {
            // The client creates a projectile ~0.8 s early, parked at (0, 0) through its start
            // cycle; it has a real position only from the cycle after.
            if (cycle <= b.startCycle)
            {
                continue;
            }
            lines.add(ballLine(cycle, b));
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
            if (!spawned.contains(a.name))
            {
                continue;
            }
            int hash = a.hash();
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

    private static Map<String, Object> ballLine(int cycle, Ball b)
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
        return m;
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
