package com.rfl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Pair state machine over per-frame player meshes (see {@link PosedMesh}). A collision starts on
 * the first update any triangles of two meshes touch while either player holds a handegg, and holds
 * until their bounds separate, so walking through someone (surfaces cross going in and out, nothing
 * crosses in between) is one collision. Dropping the handegg mid-collision does not end it.
 *
 * <p>Every pair in view is treated alike, the local player's included. Each finished collision is
 * queued as an {@link Collision} (with names) for {@link #takeFinished}. Open ones also
 * finish when the meshes go away or {@link #flush} is called.
 *
 * <p>Max triangles is the number of touching triangle pairs, capped at {@link PosedMesh#MAX_HITS}.
 * A full count is expensive on heavily overlapping models, so it is only taken when something uses
 * it: the update a collision starts, the first update of each game tick (a sample), and every
 * update while {@code detail} is on (the hitbox overlay, debug panel or debug log is showing the
 * count). Other updates stop at the first touching pair. A finished collision carries the largest
 * of the start count and the per-tick samples, so the saved value never depends on display settings.
 *
 * <p>A pair is checked only while one of them holds a handegg, while its collision is open, or
 * while {@code others} is set (Show hitboxes or Show touching triangles). Each pair's whole-mesh
 * bounds are checked before any triangles.
 *
 * <p>Names are expected to already be {@code Text.sanitize}d by the caller. Client thread only.
 */
final class ContactTracker
{
    /**
     * One pair from the latest update whose whole-mesh bounds overlap. {@code triangles} is the
     * number of touching triangle pairs: above 0 means a contact. On an update that stopped at the
     * first touching pair (see the class doc) it is 1 while in contact.
     */
    static final class Overlap
    {
        final String a;
        final String b;
        final int triangles;
        /** Touching triangles this update; null when none. */
        final PosedMesh.Hits hits;

        Overlap(String a, String b, PosedMesh.Hits hits)
        {
            this.a = a;
            this.b = b;
            this.hits = hits;
            this.triangles = hits == null ? 0 : hits.count;
        }
    }

    /** Maps a scene x/y to the world tile {x, y, plane} (WorldPoint.fromLocalInstance in game). */
    interface Tiles
    {
        int[] at(double sceneX, double sceneY);
    }

    /** One open collision. */
    private static final class Open
    {
        final long startMs;
        final int startTick;
        final int world;
        final List<String> ball;
        int max;
        int[] tile;

        Open(long startMs, int startTick, int world, List<String> ball, int max, int[] tile)
        {
            this.startMs = startMs;
            this.startTick = startTick;
            this.world = world;
            this.ball = ball;
            this.max = max;
            this.tile = tile;
        }
    }

    private final Tiles tiles;
    private final IntSupplier world;

    /** pairKey of each open collision. */
    private final Map<String, Open> open = new HashMap<>();

    /** Collisions finished since the last {@link #takeFinished}. */
    private List<Collision> finished = new ArrayList<>();

    /** pairKey to touching triangles in the latest update; null when bounds overlap but nothing touches. */
    private Map<String, PosedMesh.Hits> latest = new HashMap<>();

    /** Tick of the last sample; a new tick makes the next update sample. */
    private int sampledTick = -1;

    private long meshNanos;

    ContactTracker(Tiles tiles)
    {
        this(tiles, () -> 0);
    }

    /** @param world current world number, stamped on collisions */
    ContactTracker(Tiles tiles, IntSupplier world)
    {
        this.tiles = tiles;
        this.world = world;
    }

    /**
     * @param meshes  this frame's meshes by sanitized name
     * @param holders sanitized names of the players holding a handegg this frame
     * @param others  true to check every pair (a display is on), not just handegg ones
     * @param now     epoch ms stamped on collisions
     * @param tick    game tick count, stamped on collisions and used to sample once per tick
     * @param detail  true when a display needs the full touching count every update
     * @return the touching triangles of each collision that started this update
     */
    List<PosedMesh.Hits> update(Map<String, PosedMesh> meshes, Set<String> holders, boolean others,
        long now, int tick, boolean detail)
    {
        boolean sample = tick != sampledTick;
        sampledTick = tick;
        latest = currentPairs(meshes, holders, others, sample || detail);
        List<PosedMesh.Hits> started = new ArrayList<>();

        for (Map.Entry<String, PosedMesh.Hits> entry : latest.entrySet())
        {
            PosedMesh.Hits hits = entry.getValue();
            if (hits == null)
            {
                // Bounds still overlap but no surfaces cross: the bodies are inside each other
                // mid-pass. An open collision holds (it ends only once the bounds separate).
                continue;
            }
            Open o = open.get(entry.getKey());
            if (o != null)
            {
                o.tile = tileOf(hits);
                if (sample && hits.count > o.max)
                {
                    o.max = hits.count;
                }
                continue;
            }
            List<String> ball = new ArrayList<>();
            for (String name : splitKey(entry.getKey()))
            {
                if (holders.contains(name))
                {
                    ball.add(name);
                }
            }
            if (!ball.isEmpty())
            {
                open.put(entry.getKey(), new Open(now, tick, world.getAsInt(), ball, hits.count, tileOf(hits)));
                started.add(hits);
            }
        }

        List<String> ended = new ArrayList<>();
        for (String key : open.keySet())
        {
            if (!latest.containsKey(key))
            {
                ended.add(key);
            }
        }
        for (String key : ended)
        {
            finish(key, now, tick);
        }
        return started;
    }

    private void finish(String key, long now, int tick)
    {
        Open o = open.remove(key);
        String[] names = splitKey(key);
        finished.add(new Collision(names[0], names[1], o.ball, o.startMs, now, o.startTick, tick, o.world,
            o.tile[0], o.tile[1], o.tile[2], o.max));
    }

    /** Finishes every open collision now (shutdown, detection off, leaving the house). */
    void flush(long now, int tick)
    {
        for (String key : new ArrayList<>(open.keySet()))
        {
            finish(key, now, tick);
        }
    }

    /** Collisions finished since the last call, oldest first; clears them. */
    List<Collision> takeFinished()
    {
        List<Collision> result = finished;
        finished = new ArrayList<>();
        return result;
    }

    /**
     * Drops every open and unclaimed finished collision. Call {@link #flush} and
     * {@link #takeFinished} first to keep them.
     */
    void reset()
    {
        open.clear();
        finished = new ArrayList<>();
        latest = new HashMap<>();
        sampledTick = -1;
    }

    /** Nanoseconds spent in triangle checks since the last call; resets the counter. */
    long takeMeshNanos()
    {
        long n = meshNanos;
        meshNanos = 0;
        return n;
    }

    /** Collisions in progress right now, as "A ↔ B", sorted. */
    List<String> inProgress()
    {
        List<String> pairs = new ArrayList<>();
        for (String key : open.keySet())
        {
            String[] names = splitKey(key);
            pairs.add(names[0] + " ↔ " + names[1]);
        }
        Collections.sort(pairs);
        return pairs;
    }

    /** Every pair from the latest update whose bounds overlap, touching or not. */
    List<Overlap> overlaps()
    {
        List<Overlap> result = new ArrayList<>();
        for (Map.Entry<String, PosedMesh.Hits> entry : latest.entrySet())
        {
            String[] names = splitKey(entry.getKey());
            result.add(new Overlap(names[0], names[1], entry.getValue()));
        }
        return result;
    }

    /** Touching triangles of a pair (either order) in the latest update, or null. */
    PosedMesh.Hits hits(String a, String b)
    {
        return latest.get(a.compareTo(b) <= 0 ? pairKey(a, b) : pairKey(b, a));
    }

    /** Name to the names whose triangles touch theirs in the latest update. */
    Map<String, List<String>> collidingNow()
    {
        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<String, PosedMesh.Hits> entry : latest.entrySet())
        {
            if (entry.getValue() == null)
            {
                continue;
            }
            String[] pair = splitKey(entry.getKey());
            result.computeIfAbsent(pair[0], k -> new ArrayList<>()).add(pair[1]);
            result.computeIfAbsent(pair[1], k -> new ArrayList<>()).add(pair[0]);
        }
        return result;
    }

    /**
     * Every checked pair whose bounds overlap, with its touching triangles (see the class doc for
     * which pairs are checked). A pair that could start now (handegg, not open) is fully counted,
     * since its start records a count and a centroid tile; the rest per {@code fullCount}.
     */
    private Map<String, PosedMesh.Hits> currentPairs(Map<String, PosedMesh> meshes, Set<String> holders,
        boolean others, boolean fullCount)
    {
        Map<String, PosedMesh.Hits> pairs = new HashMap<>();
        List<String> names = new ArrayList<>(meshes.keySet());
        long start = System.nanoTime();

        for (int i = 0; i < names.size(); i++)
        {
            for (int j = i + 1; j < names.size(); j++)
            {
                String x = names.get(i);
                String y = names.get(j);
                boolean handegg = holders.contains(x) || holders.contains(y);
                String a = x.compareTo(y) <= 0 ? x : y;
                String b = x.compareTo(y) <= 0 ? y : x;
                String key = pairKey(a, b);
                // An open collision stays checked after the handegg is dropped, so it ends on separation.
                boolean isOpen = open.containsKey(key);
                if (!handegg && !others && !isOpen)
                {
                    continue;
                }
                PosedMesh ma = meshes.get(a);
                PosedMesh mb = meshes.get(b);
                if (PosedMesh.overlap(ma.bounds, mb.bounds) != null)
                {
                    int limit = fullCount || handegg && !isOpen ? PosedMesh.MAX_HITS : 1;
                    pairs.put(key, PosedMesh.intersect(ma, mb, limit));
                }
            }
        }

        meshNanos += System.nanoTime() - start;
        return pairs;
    }

    private int[] tileOf(PosedMesh.Hits hits)
    {
        double[] c = hits.centroid();
        return tiles.at(c[0], c[1]);
    }

    private static String pairKey(String a, String b)
    {
        return a + '\u0000' + b;
    }

    private static String[] splitKey(String key)
    {
        return key.split("\u0000", 2);
    }
}
