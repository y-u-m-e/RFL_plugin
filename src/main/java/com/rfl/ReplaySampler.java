package com.rfl;

import java.util.ArrayList;
import java.util.Arrays;
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

        PlayerState(String name, int x, int y, int orient, int anim, int animFrame, int pose, int poseFrame)
        {
            this.name = name;
            this.x = x;
            this.y = y;
            this.orient = orient;
            this.anim = anim;
            this.animFrame = animFrame;
            this.pose = pose;
            this.poseFrame = poseFrame;
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
    private Set<String> present = new LinkedHashSet<>();
    /** Names {@link #frame} has currently spawned; only its own spawn emission adds to this. */
    private final Set<String> spawned = new LinkedHashSet<>();

    /** Lines for one ClientTick: despawn, spawn, f, then ball lines, in that order. */
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

        for (Ball b : balls)
        {
            lines.add(ballLine(cycle, b));
        }

        present = now;
        return lines;
    }

    /**
     * Lines for one GameTick: tick, then app lines for spawned players whose appearance
     * changed. An appearance for a name {@link #frame} hasn't spawned (yet, or anymore) is
     * skipped and never assigned an index.
     */
    List<Map<String, Object>> tick(int cycle, int tick, List<Appearance> appearances)
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

        return lines;
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
