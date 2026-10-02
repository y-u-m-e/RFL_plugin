package com.rfl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Pair state machine over per-frame player meshes (see {@link PosedMesh}). A contact starts on the
 * first update any triangles of the two meshes touch and holds until their bounds separate, so walking
 * through someone (surfaces cross going in and out, nothing crosses in between) is one contact.
 *
 * <p>Depth is the number of touching triangle pairs, capped at {@link PosedMesh#MAX_HITS}. A full
 * count is expensive on heavily overlapping models, so it is only taken when something uses it:
 * the update a contact starts (the contact_start depth), the first update of each game tick (a
 * depth sample), and every update while {@code detail} is on (the hitbox overlay, debug panel or
 * debug log is showing the count). Other updates stop at the first touching pair. contact_end
 * carries the largest of the start depth and the per-tick samples, so the reported value never
 * depends on display settings.
 *
 * <p>Self only: contact events are emitted only for pairs that include the local player
 * ({@code self}), and a contact starts only while either body holds a handegg ({@code holders});
 * every started contact gets its contact_end. Each carries a per-client {@code contactId} (stable
 * while the contact lasts), the world tile under the touching-triangle centroid and who held the
 * handegg ({@code ball}), never the other player's name; names are local map keys only.
 *
 * <p>Pairs of two other players are checked only while one of them holds a handegg (for the
 * name-free {@code collision_seen} witness event, once per pair contact) or while {@code others} is
 * set (Show hitboxes or Show touching triangles). Each pair's whole-mesh bounds are checked before any triangles.
 *
 * <p>Observer mode ({@code observe}): no new events (open self contacts end, nothing starts, no
 * collision_seen). Instead every pair, self or not, is tracked locally with the same handegg-gated
 * mesh-touch start and bounds-separate end, and each finished one is queued as an
 * {@link ObservedCollision} (with names) for {@link #takeObserved}. Open ones also finish when
 * observe goes off, the meshes go away, or {@link #flushObserved} is called.
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

    /** One open self contact. */
    private static final class Contact
    {
        final int id;
        final String ball;
        /** Largest sampled depth since the contact started. */
        int depth;
        /** World tile {x, y, plane} under the latest touching-triangle centroid. */
        int[] tile;

        Contact(int id, String ball, int depth, int[] tile)
        {
            this.id = id;
            this.ball = ball;
            this.depth = depth;
            this.tile = tile;
        }
    }

    /** One open observer collision. */
    private static final class Observed
    {
        final long startMs;
        final int startTick;
        final int world;
        final List<String> ball;
        int max;
        int[] tile;

        Observed(long startMs, int startTick, int world, List<String> ball, int max, int[] tile)
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

    /** pairKey of each open observer collision. */
    private final Map<String, Observed> observed = new HashMap<>();

    /** Observer collisions finished since the last {@link #takeObserved}. */
    private List<ObservedCollision> finished = new ArrayList<>();

    /** pairKey of each open self contact. */
    private final Map<String, Contact> active = new HashMap<>();

    /** pairKeys of other-other pairs touching with a handegg that already emitted collision_seen. */
    private Set<String> witnessed = new HashSet<>();

    /** Next contactId; never reset, so ids stay unique for the client's lifetime. */
    private int nextId;

    /** pairKey to touching triangles in the latest update; null when bounds overlap but nothing touches. */
    private Map<String, PosedMesh.Hits> latest = new HashMap<>();

    /** Tick of the last depth sample; a new tick makes the next update sample. */
    private int sampledTick = -1;

    private long meshNanos;

    ContactTracker(Tiles tiles)
    {
        this(tiles, () -> 0);
    }

    /** @param world current world number, stamped on observer collisions */
    ContactTracker(Tiles tiles, IntSupplier world)
    {
        this.tiles = tiles;
        this.world = world;
    }

    /** Reporting-mode update (observe off). */
    List<RflEvent> update(Map<String, PosedMesh> meshes, String self, Set<String> holders, boolean others,
        long now, int tick, boolean detail)
    {
        return update(meshes, self, holders, others, now, tick, detail, false);
    }

    /**
     * @param meshes  this frame's meshes by sanitized name
     * @param self    the local player's sanitized name; null means no self pairs (every contact ends)
     * @param holders sanitized names of the players holding a handegg this frame
     * @param others  true to check every other-other pair (a display is on), not just handegg ones
     * @param now     epoch ms stamped on any events
     * @param tick    game tick count, stamped on events and used to sample depth once per tick
     * @param detail  true when a display needs the full touching count every update
     * @param observe Observer mode: track every pair locally, start nothing reportable
     * @return collision_seen, contact_start and contact_end events, in that order; in observer
     *         mode only the contact_end of self contacts that were open when it went on
     */
    List<RflEvent> update(Map<String, PosedMesh> meshes, String self, Set<String> holders, boolean others,
        long now, int tick, boolean detail, boolean observe)
    {
        boolean sample = tick != sampledTick;
        sampledTick = tick;
        latest = currentPairs(meshes, self, holders, others, sample || detail, observe);
        observePairs(holders, now, tick, sample, observe);
        List<RflEvent> events = new ArrayList<>();
        Set<String> stillWitnessed = new HashSet<>();

        for (Map.Entry<String, PosedMesh.Hits> entry : latest.entrySet())
        {
            PosedMesh.Hits hits = entry.getValue();
            String key = entry.getKey();
            if (hits == null)
            {
                // Bounds still overlap but no surfaces cross: the bodies are inside each other
                // mid-pass. A witnessed pair stays witnessed (open contacts are held below).
                if (witnessed.contains(key))
                {
                    stillWitnessed.add(key);
                }
                continue;
            }
            if (observe)
            {
                continue;
            }
            String[] names = splitKey(key);
            boolean selfPair = names[0].equals(self) || names[1].equals(self);
            boolean handegg = holders.contains(names[0]) || holders.contains(names[1]);

            if (!selfPair)
            {
                if (handegg)
                {
                    stillWitnessed.add(key);
                    if (!witnessed.contains(key))
                    {
                        int[] tile = tileOf(hits);
                        events.add(RflEvent.collisionSeen(now, tick, tile[0], tile[1], tile[2]));
                    }
                }
                continue;
            }

            Contact contact = active.get(key);
            if (contact == null)
            {
                if (handegg)
                {
                    int[] tile = tileOf(hits);
                    String ball = holders.contains(self) ? "self" : "other";
                    contact = new Contact(nextId++, ball, hits.count, tile);
                    active.put(key, contact);
                    events.add(RflEvent.contactStart(now, tick, contact.id, tile[0], tile[1], tile[2],
                        hits.count, ball));
                }
                continue;
            }
            contact.tile = tileOf(hits);
            if (sample && hits.count > contact.depth)
            {
                contact.depth = hits.count;
            }
        }
        witnessed = stillWitnessed;

        List<String> ended = new ArrayList<>();
        for (Map.Entry<String, Contact> entry : active.entrySet())
        {
            // A contact holds while the two meshes' bounds overlap, touching or not, and ends once
            // they separate. Walking through someone crosses surfaces going in and coming out with
            // nothing crossing in between, which would otherwise read as two contacts.
            // A self change (or null self) ends every contact: its key is no longer a self pair.
            if (observe || !latest.containsKey(entry.getKey()) || self == null
                || !isSelfPair(entry.getKey(), self))
            {
                ended.add(entry.getKey());
            }
        }
        for (String key : ended)
        {
            Contact c = active.remove(key);
            events.add(RflEvent.contactEnd(now, tick, c.id, c.tile[0], c.tile[1], c.tile[2], c.depth, c.ball));
        }

        return events;
    }

    /**
     * Observer pairs: a touching pair with a handegg holder starts (unless open), an open one
     * follows the centroid tile and the per-tick max count, and one whose bounds no longer
     * overlap (or every one, with observe off) finishes.
     */
    private void observePairs(Set<String> holders, long now, int tick, boolean sample, boolean observe)
    {
        if (observe)
        {
            for (Map.Entry<String, PosedMesh.Hits> entry : latest.entrySet())
            {
                PosedMesh.Hits hits = entry.getValue();
                if (hits == null)
                {
                    continue;
                }
                Observed o = observed.get(entry.getKey());
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
                    observed.put(entry.getKey(), new Observed(now, tick, world.getAsInt(), ball, hits.count,
                        tileOf(hits)));
                }
            }
        }
        List<String> ended = new ArrayList<>();
        for (String key : observed.keySet())
        {
            if (!observe || !latest.containsKey(key))
            {
                ended.add(key);
            }
        }
        for (String key : ended)
        {
            finish(key, now, tick);
        }
    }

    private void finish(String key, long now, int tick)
    {
        Observed o = observed.remove(key);
        String[] names = splitKey(key);
        finished.add(new ObservedCollision(names[0], names[1], o.ball, o.startMs, now, o.startTick, tick, o.world,
            o.tile[0], o.tile[1], o.tile[2], o.max));
    }

    /** Finishes every open observer collision now (shutdown, observer off, leaving the house). */
    void flushObserved(long now, int tick)
    {
        for (String key : new ArrayList<>(observed.keySet()))
        {
            finish(key, now, tick);
        }
    }

    /** Observer collisions finished since the last call, oldest first; clears them. */
    List<ObservedCollision> takeObserved()
    {
        List<ObservedCollision> result = finished;
        finished = new ArrayList<>();
        return result;
    }

    /**
     * Drops every open contact and observer collision without events. Keeps the id counter, so
     * ids are never reused. Call {@link #flushObserved} first to keep the open observer ones.
     */
    void reset()
    {
        active.clear();
        observed.clear();
        finished = new ArrayList<>();
        witnessed = new HashSet<>();
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

    /** Pairs being tracked as observer collisions right now, as "A ↔ B", sorted. */
    List<String> observing()
    {
        List<String> pairs = new ArrayList<>();
        for (String key : observed.keySet())
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

    /** Touching triangles of an open contact this update, or null when not open or not touching. */
    PosedMesh.Hits hits(int contactId)
    {
        for (Map.Entry<String, Contact> entry : active.entrySet())
        {
            if (entry.getValue().id == contactId)
            {
                return latest.get(entry.getKey());
            }
        }
        return null;
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
     * Every checked pair whose bounds overlap, with its touching triangles. Self pairs are always
     * checked; other-other pairs only with a handegg holder or {@code others}. A pair that could
     * start now (self, handegg, not yet open; or other-other, handegg, not yet witnessed) is fully
     * counted, since its start reports depth or a centroid tile; the rest per {@code fullCount}.
     */
    private Map<String, PosedMesh.Hits> currentPairs(Map<String, PosedMesh> meshes, String self,
        Set<String> holders, boolean others, boolean fullCount, boolean observe)
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
                boolean selfPair = x.equals(self) || y.equals(self);
                boolean handegg = holders.contains(x) || holders.contains(y);
                String a = x.compareTo(y) <= 0 ? x : y;
                String b = x.compareTo(y) <= 0 ? y : x;
                String key = pairKey(a, b);
                // An open observer collision stays checked after the handegg is dropped, so it
                // ends on separation like a self contact does.
                if (!selfPair && !handegg && !others && !observed.containsKey(key))
                {
                    continue;
                }
                PosedMesh ma = meshes.get(a);
                PosedMesh mb = meshes.get(b);
                if (PosedMesh.overlap(ma.bounds, mb.bounds) != null)
                {
                    boolean canStart = handegg && (observe ? !observed.containsKey(key)
                        : selfPair ? !active.containsKey(key) : !witnessed.contains(key));
                    int limit = fullCount || canStart ? PosedMesh.MAX_HITS : 1;
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

    private static boolean isSelfPair(String key, String self)
    {
        String[] names = splitKey(key);
        return names[0].equals(self) || names[1].equals(self);
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
