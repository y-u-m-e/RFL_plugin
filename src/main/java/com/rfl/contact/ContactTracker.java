package com.rfl.contact;

import com.rfl.incomplete.IncompleteDetector;
import com.rfl.teams.Teams;
import sh.yumekui.toolkit.geom.TriangleMesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.IntSupplier;

/**
 * Pair state machine over per-frame player meshes (see {@link TriangleMesh}). A collision starts on
 * the first update any triangles of two meshes touch while either player holds a handegg, and holds
 * until their bounds separate, so walking through someone (surfaces cross going in and out, nothing
 * crosses in between) is one collision. Dropping the handegg mid-collision does not end it.
 *
 * <p>Only a pair with a handegg held by either player is triangle-checked, the local player's own
 * pairs included; a pair with no holder is never checked, display or not. Once a collision opens
 * it stays checked until the bounds separate, even if the handegg is dropped. Each finished
 * collision is queued as an {@link Collision} (with names) for {@link #takeFinished}. Open ones
 * also finish when the meshes go away or {@link #flush} is called.
 *
 * <p>Max triangles is the number of touching triangle pairs, capped at {@link #MAX_TOUCHING_PAIRS}.
 * A full count is expensive on heavily overlapping models, so it is only taken when something uses
 * it: the update a collision starts, the first update of each game tick (a sample), and every
 * update while {@code detail} is on (the hitbox or touching-triangle overlay is showing the
 * count). Other updates stop at the first touching pair. A finished collision carries the largest
 * of the start count and the per-tick samples, so the saved value never depends on display settings.
 *
 * <p>{@code detail} only changes how fully an already-checked pair is counted; it never widens
 * which pairs get checked. Show hitboxes and Show touching triangles need the full count on
 * handegg pairs to fill the overlay completely, but a pair with no handegg holder stays
 * untouched, even while they're on. Each pair's whole-mesh bounds are checked before any triangles.
 *
 * <p>Names are expected to already be sanitized by the caller ({@code PlayerNames.sanitized}). Client thread only.
 */
public final class ContactTracker
{
    /**
     * One pair from the latest update whose whole-mesh bounds overlap. {@code triangles} is the
     * number of touching triangle pairs: above 0 means a contact. On an update that stopped at the
     * first touching pair (see the class doc) it is 1 while in contact.
     */
    public static final class Overlap
    {
        final String a;
        final String b;
        final int triangles;
        /** Touching triangles this update; null when none. */
        public final TriangleMesh.Hits hits;

        Overlap(String a, String b, TriangleMesh.Hits hits)
        {
            this.a = a;
            this.b = b;
            this.hits = hits;
            this.triangles = hits == null ? 0 : hits.count;
        }
    }

    /** Maps a local x/y to its {@link TileRef} (WorldPoint.fromLocalInstance in game). */
    public interface Tiles
    {
        TileRef at(double localX, double localY);
    }

    /** One open collision. */
    private static final class Open
    {
        final long startMs;
        final int startTick;
        final int world;
        final List<String> ball;
        int max;
        TileRef tile;

        Open(long startMs, int startTick, int world, List<String> ball, int max, TileRef tile)
        {
            this.startMs = startMs;
            this.startTick = startTick;
            this.world = world;
            this.ball = ball;
            this.max = max;
            this.tile = tile;
        }
    }

    /**
     * The incomplete rule looks back at most {@link IncompleteDetector#CONTACT_GRACE_CYCLES} cycles,
     * so a touch older than this many cycles can be forgotten; kept well above the grace.
     */
    private static final int LAST_TOUCH_KEEP_CYCLES = 50;
    /**
     * At most this many touching triangle pairs are counted per player pair. The count is saved as a
     * collision's maxTriangles, so a value of 512 means "at least 512"; real recordings often reach it.
     */
    static final int MAX_TOUCHING_PAIRS= 512;
    /** Joins a pair's names in map keys; NUL never appears in a player name. */
    private static final char PAIR_SEPARATOR = '\u0000';
    /** {@link #lastTouch} is only pruned once it holds more pairs than this, so most frames skip it. */
    private static final int LAST_TOUCH_PRUNE_SIZE = 32;

    private final Tiles tiles;
    private final IntSupplier world;
    /** Reused narrow-phase buffers; client thread only, like the tracker. */
    private final TriangleMesh.Intersector intersector = new TriangleMesh.Intersector();

    /** pairKey of each open collision. */
    private final Map<String, Open> open = new HashMap<>();
    /** pairKey to the client cycle its triangles last touched, for the incomplete contact grace. */
    private final Map<String, Integer> lastTouch = new HashMap<>();
    /** Client cycle of the current update ({@link #setCycle}). */
    private int cycle;
    /**
     * A handegg in flight's likely receiver(s) ({@link #setFlightReceivers}): their pairs are
     * triangle-checked for the incomplete grace even before their weapon slot shows the egg. Such
     * touches only feed {@link #contactsAt}; they never start, draw or save a collision.
     */
    private Set<String> flightReceivers = Collections.emptySet();

    /** Collisions finished since the last {@link #takeFinished}. */
    private List<Collision> finished = new ArrayList<>();

    /** pairKey to touching triangles in the latest update; null when bounds overlap but nothing touches. */
    private Map<String, TriangleMesh.Hits> latest = new HashMap<>();

    /** Tick of the last sample; a new tick makes the next update sample. */
    private int sampledTick = -1;

    /**
     * Which pairs may collide at all (the team gate, {@link Teams#opposing}); a pair it rejects is
     * never checked, so it can't start, stay open, be drawn, or be an incomplete's contact.
     */
    private BiPredicate<String, String> pairFilter = (a, b) -> true;

    /** @param world current world number, stamped on collisions */
    public ContactTracker(Tiles tiles, IntSupplier world)
    {
        this.tiles = tiles;
        this.world = world;
    }

    /**
     * @param meshes  this frame's meshes by sanitized name
     * @param holders sanitized names of the players holding a handegg this frame
     * @param now     epoch ms stamped on collisions
     * @param tick    game tick count, stamped on collisions and used to sample once per tick
     * @param detail  true when a display needs the full touching count on every checked pair
     * @return the touching triangles of each collision that started this update
     */
    public List<TriangleMesh.Hits> update(Map<String, TriangleMesh> meshes, Set<String> holders, long now, int tick,
        boolean detail)
    {
        boolean sample = tick != sampledTick;
        sampledTick = tick;
        latest = currentPairs(meshes, holders, sample || detail);
        List<TriangleMesh.Hits> started = new ArrayList<>();

        for (Map.Entry<String, TriangleMesh.Hits> entry : latest.entrySet())
        {
            TriangleMesh.Hits hits = entry.getValue();
            if (hits != null)
            {
                lastTouch.put(entry.getKey(), cycle);
            }
            if (hits == null)
            {
                // Bounds still overlap but no surfaces cross: the bodies are inside each other
                // mid-pass. An open collision holds (it ends only once the bounds separate).
                continue;
            }
            Open collision = open.get(entry.getKey());
            if (collision != null)
            {
                collision.tile = tileOf(hits);
                if (sample && hits.count > collision.max)
                {
                    collision.max = hits.count;
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
        touchFlightReceivers(meshes);
        if (lastTouch.size() > LAST_TOUCH_PRUNE_SIZE)
        {
            lastTouch.values().removeIf(at -> at < cycle - LAST_TOUCH_KEEP_CYCLES);
        }
        return started;
    }

    /**
     * Pairs involving a flight receiver that the normal pass skipped (nobody holds an egg yet):
     * bounds, then a one-hit triangle test, team gate applied. Bounded by receivers x players and
     * free when nothing is in flight.
     */
    private void touchFlightReceivers(Map<String, TriangleMesh> meshes)
    {
        if (flightReceivers.isEmpty())
        {
            return;
        }
        for (String receiver : flightReceivers)
        {
            if (!meshes.containsKey(receiver))
            {
                continue;
            }
            for (String other : meshes.keySet())
            {
                if (other.equals(receiver) || !pairFilter.test(receiver, other))
                {
                    continue;
                }
                String first = receiver.compareTo(other) <= 0 ? receiver : other;
                String second = receiver.compareTo(other) <= 0 ? other : receiver;
                String key = pairKey(first, second);
                if (latest.containsKey(key))
                {
                    continue;
                }
                TriangleMesh firstMesh = meshes.get(first);
                TriangleMesh secondMesh = meshes.get(second);
                // A limit of 1 asks only "touching at all?", which is all the grace window needs.
                if (TriangleMesh.boundsOverlap(firstMesh, secondMesh)
                    && intersector.intersect(firstMesh, secondMesh, 1) != null)
                {
                    lastTouch.put(key, cycle);
                }
            }
        }
    }

    /** Sets {@link #flightReceivers} for the next updates; empty when no handegg is in flight. */
    public void setFlightReceivers(Set<String> receivers)
    {
        flightReceivers = receivers == null ? Collections.emptySet() : receivers;
    }

    /** The client cycle the next {@link #update} happens on, for {@link #contactsAt}. */
    public void setCycle(int cycle)
    {
        this.cycle = cycle;
    }

    /**
     * Who each player counts as in contact with at {@code at} for the incomplete rule: an open
     * collision (it holds while the bounds overlap, through frames with no crossing triangles), or
     * triangles that touched within {@code grace} cycles before it, inclusive. A touch after
     * {@code at} never counts. Only pairs the team gate allows are ever tracked.
     */
    public Map<String, List<String>> contactsAt(int at, int grace)
    {
        Set<String> keys = new java.util.TreeSet<>(open.keySet());
        for (Map.Entry<String, Integer> touch : lastTouch.entrySet())
        {
            if (touch.getValue() <= at && touch.getValue() >= at - grace)
            {
                keys.add(touch.getKey());
            }
        }
        Map<String, List<String>> result = new HashMap<>();
        for (String key : keys)
        {
            String[] pair = splitKey(key);
            result.computeIfAbsent(pair[0], k -> new ArrayList<>()).add(pair[1]);
            result.computeIfAbsent(pair[1], k -> new ArrayList<>()).add(pair[0]);
        }
        return result;
    }

    private void finish(String key, long now, int tick)
    {
        Open collision = open.remove(key);
        String[] names = splitKey(key);
        finished.add(new Collision(names[0], names[1], collision.ball, collision.startMs, now, collision.startTick,
            tick, collision.world,
            collision.tile.worldX, collision.tile.worldY, collision.tile.plane, collision.tile.sceneX,
            collision.tile.sceneY, collision.max));
    }

    /** Finishes every open collision now (shutdown, detection off, leaving the house). */
    public void flush(long now, int tick)
    {
        for (String key : new ArrayList<>(open.keySet()))
        {
            finish(key, now, tick);
        }
    }

    /** Collisions finished since the last call, oldest first; clears them. */
    public List<Collision> takeFinished()
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
        lastTouch.clear();
        open.clear();
        finished = new ArrayList<>();
        latest = new HashMap<>();
        sampledTick = -1;
    }

    /** Sets {@link #pairFilter}; it is asked with the two sanitized names, in either order. */
    public void setPairFilter(BiPredicate<String, String> filter)
    {
        pairFilter = filter;
    }

    /** Every pair from the latest update whose bounds overlap, touching or not. */
    public List<Overlap> overlaps()
    {
        List<Overlap> result = new ArrayList<>();
        for (Map.Entry<String, TriangleMesh.Hits> entry : latest.entrySet())
        {
            String[] names = splitKey(entry.getKey());
            result.add(new Overlap(names[0], names[1], entry.getValue()));
        }
        return result;
    }

    /**
     * Every checked pair whose bounds overlap, with its touching triangles (see the class doc for
     * which pairs are checked). A pair that could start now (handegg, not open) is fully counted,
     * since its start records a count and a centroid tile; the rest per {@code fullCount}.
     */
    private Map<String, TriangleMesh.Hits> currentPairs(Map<String, TriangleMesh> meshes, Set<String> holders,
        boolean fullCount)
    {
        Map<String, TriangleMesh.Hits> pairs = new HashMap<>();
        List<String> names = new ArrayList<>(meshes.keySet());

        for (int i = 0; i < names.size(); i++)
        {
            for (int j = i + 1; j < names.size(); j++)
            {
                String one = names.get(i);
                String two = names.get(j);
                if (!pairFilter.test(one, two))
                {
                    continue;
                }
                boolean handegg = holders.contains(one) || holders.contains(two);
                // Keys and collisions always name the pair in alphabetical order.
                String first = one.compareTo(two) <= 0 ? one : two;
                String second = one.compareTo(two) <= 0 ? two : one;
                String key = pairKey(first, second);
                // An open collision stays checked after the handegg is dropped, so it ends on separation.
                boolean isOpen = open.containsKey(key);
                if (!handegg && !isOpen)
                {
                    continue;
                }
                TriangleMesh firstMesh = meshes.get(first);
                TriangleMesh secondMesh = meshes.get(second);
                if (TriangleMesh.boundsOverlap(firstMesh, secondMesh))
                {
                    // A pair that may start now needs its full count for the saved maxTriangles.
                    int limit = fullCount || handegg && !isOpen ? MAX_TOUCHING_PAIRS : 1;
                    pairs.put(key, intersector.intersect(firstMesh, secondMesh, limit));
                }
            }
        }

        return pairs;
    }

    private TileRef tileOf(TriangleMesh.Hits hits)
    {
        double[] centre = hits.centroid();
        return tiles.at(centre[0], centre[1]);
    }

    /** One map key per pair, the names joined by a character no name can contain. */
    private static String pairKey(String first, String second)
    {
        return first + PAIR_SEPARATOR + second;
    }

    private static String[] splitKey(String key)
    {
        return key.split(String.valueOf(PAIR_SEPARATOR), 2);
    }
}
