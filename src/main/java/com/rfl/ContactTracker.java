package com.rfl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pair state machine over per-frame player bodies (see {@link Body}). Names are expected to already be
 * {@code Text.sanitize}d by the caller (Task 4) so two clients derive the identical pair key
 * regardless of non-breaking spaces in the raw RSN.
 *
 * Pairwise over all keys — at the Hub's own render-distance player cap this is at most 45
 * pairs, so no broad-phase spatial index is needed.
 */
final class ContactTracker
{
    /**
     * Penetration (local units, 128 = one tile) of a pair's deepest part pair before it counts as
     * a contact. PROVISIONAL: the old whole-body cylinder used 40 (measured 2026-09-29), but body
     * parts are thinner so depths shrink. Must be re-measured in game from the per-tick
     * "[RFL debug] ... depth" lines. Once started, a contact holds until every part separates, so
     * it doesn't flicker around the threshold.
     */
    static final int START_DEPTH = 12;

    /**
     * Grown onto each overlapping capsule pair's shared bounds when picking candidate triangles in
     * CAPSULES_AND_MESH, since model triangles (armour, capes) can stick out past the capsules.
     */
    static final float MESH_REGION_MARGIN = 8;

    /**
     * One pair from the latest update: overlapping capsules, or (MESH mode) touching triangles.
     * {@code contact} once it counts as a contact. {@code partA}/{@code partB} name the deepest
     * part pair (partA belongs to a).
     */
    static final class Overlap
    {
        final String a;
        final String b;
        final String partA;
        final String partB;
        final int depth;
        final boolean contact;
        /** Triangles touching this update; null when the mode didn't check. */
        final Boolean mesh;
        /** Why the pair is or isn't a contact, for the debug panel. */
        final String why;
        /** Touching triangles this update, for drawing; null when none or not checked. */
        final PosedMesh.Hits hits;

        Overlap(String a, String b, String partA, String partB, int depth, boolean contact, Boolean mesh, String why,
            PosedMesh.Hits hits)
        {
            this.hits = hits;
            this.a = a;
            this.b = b;
            this.partA = partA;
            this.partB = partB;
            this.depth = depth;
            this.contact = contact;
            this.mesh = mesh;
            this.why = why;
        }
    }

    /** What one update saw for a pair. */
    private static final class Pair
    {
        /** Deepest capsule pair; null when either body has no parts. */
        final Body.Contact capsule;
        /** Capsule penetration, rounded; 0 when apart. */
        final int depth;
        final Boolean mesh;
        final PosedMesh.Hits hits;
        /** The mode's contact condition this update, without the hold. */
        final boolean colliding;

        Pair(Body.Contact capsule, int depth, Boolean mesh, PosedMesh.Hits hits, boolean colliding)
        {
            this.capsule = capsule;
            this.depth = depth;
            this.mesh = mesh;
            this.hits = hits;
            this.colliding = colliding;
        }
    }

    private RflConfig.ContactMode mode = RflConfig.ContactMode.CAPSULES;

    // pairKey -> max overlap depth seen since the pair became active.
    private final Map<String, Integer> active = new HashMap<>();

    // pairKey -> what the latest update saw (overlapping capsules, or touching triangles in MESH).
    private Map<String, Pair> latest = new HashMap<>();

    private long meshNanos;
    private int maxMeshHits = PosedMesh.MAX_HITS;
    private boolean displayMesh;

    void setMode(RflConfig.ContactMode mode)
    {
        this.mode = mode;
    }

    /**
     * Also find touching triangles of every overlapping pair just to draw them (Show overlap),
     * whatever the mode. Never changes what counts as a contact.
     */
    void setDisplayMesh(boolean displayMesh)
    {
        this.displayMesh = displayMesh;
    }

    /** Triangle pairs collected per body pair: 1 is enough to decide; more only for the overlay. */
    void setMaxMeshHits(int maxMeshHits)
    {
        this.maxMeshHits = maxMeshHits;
    }

    /**
     * CAPSULES: a contact starts at START_DEPTH and holds until the capsules separate.
     * CAPSULES_AND_MESH: starts at START_DEPTH with touching triangles, then holds until the
     * capsules separate. MESH: a contact exactly while any triangles touch.
     */
    List<RflEvent> update(Map<String, Body> bodies, long now, int tick)
    {
        Map<String, Pair> current = currentPairs(bodies);
        latest = current;
        List<RflEvent> events = new ArrayList<>();

        for (Map.Entry<String, Pair> entry : current.entrySet())
        {
            String key = entry.getKey();
            Pair pair = entry.getValue();
            Integer maxSoFar = active.get(key);

            if (maxSoFar == null)
            {
                if (!pair.colliding)
                {
                    continue;
                }
                String[] names = splitKey(key);
                events.add(RflEvent.contactStart(now, tick, names[0], names[1], pair.depth));
                active.put(key, pair.depth);
            }
            else if (pair.depth > maxSoFar)
            {
                active.put(key, pair.depth);
            }
        }

        List<String> ended = new ArrayList<>();
        for (String key : active.keySet())
        {
            if (!held(current.get(key)))
            {
                ended.add(key);
            }
        }
        for (String key : ended)
        {
            String[] pair = splitKey(key);
            events.add(RflEvent.contactEnd(now, tick, pair[0], pair[1], active.remove(key)));
        }

        return events;
    }

    private boolean held(Pair pair)
    {
        if (pair == null)
        {
            return false;
        }
        if (mode == RflConfig.ContactMode.MESH)
        {
            return Boolean.TRUE.equals(pair.mesh);
        }
        return pair.capsule != null && pair.capsule.depth > 0;
    }

    void reset()
    {
        active.clear();
        latest = new HashMap<>();
    }

    /** Nanoseconds spent in triangle checks since the last call. */
    long takeMeshNanos()
    {
        long n = meshNanos;
        meshNanos = 0;
        return n;
    }

    /** Every pair from the latest update, contacts and sub-threshold grazes alike. */
    List<Overlap> overlaps()
    {
        List<Overlap> result = new ArrayList<>();
        for (Map.Entry<String, Pair> entry : latest.entrySet())
        {
            String[] names = splitKey(entry.getKey());
            Pair p = entry.getValue();
            boolean contact = active.containsKey(entry.getKey());
            result.add(new Overlap(names[0], names[1], p.capsule == null ? "-" : p.capsule.partA.name,
                p.capsule == null ? "-" : p.capsule.partB.name, p.depth, contact, p.mesh, why(p, contact), p.hits));
        }
        return result;
    }

    private String why(Pair p, boolean contact)
    {
        String depth = "depth " + p.depth;
        if (mode == RflConfig.ContactMode.MESH)
        {
            return p.hits != null ? "triangles touching" : "no triangles touching";
        }
        if (p.depth < START_DEPTH)
        {
            return contact ? "held until separated (" + depth + ")" : depth + " < " + START_DEPTH;
        }
        if (mode == RflConfig.ContactMode.CAPSULES)
        {
            return depth + " >= " + START_DEPTH;
        }
        if (p.hits != null)
        {
            return depth + " >= " + START_DEPTH + ", triangles touching";
        }
        return contact ? "held; no triangles touching" : "no triangles touching";
    }

    /** Intersecting triangles of every pair checked in the latest update. */
    List<PosedMesh.Hits> meshHits()
    {
        List<PosedMesh.Hits> result = new ArrayList<>();
        for (Pair p : latest.values())
        {
            if (p.hits != null)
            {
                result.add(p.hits);
            }
        }
        return result;
    }

    /**
     * Name to the names they are colliding with in the latest update: the mode's contact
     * condition right now. Unlike {@link #contactsByPlayer}, a contact being held does not count.
     */
    Map<String, List<String>> collidingNow()
    {
        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<String, Pair> entry : latest.entrySet())
        {
            if (!entry.getValue().colliding)
            {
                continue;
            }
            String[] pair = splitKey(entry.getKey());
            result.computeIfAbsent(pair[0], k -> new ArrayList<>()).add(pair[1]);
            result.computeIfAbsent(pair[1], k -> new ArrayList<>()).add(pair[0]);
        }
        return result;
    }

    /** Name to the names they are currently in contact with. */
    Map<String, List<String>> contactsByPlayer()
    {
        Map<String, List<String>> result = new HashMap<>();
        for (String key : active.keySet())
        {
            String[] pair = splitKey(key);
            result.computeIfAbsent(pair[0], k -> new ArrayList<>()).add(pair[1]);
            result.computeIfAbsent(pair[1], k -> new ArrayList<>()).add(pair[0]);
        }
        return result;
    }

    private Map<String, Pair> currentPairs(Map<String, Body> bodies)
    {
        Map<String, Pair> pairs = new HashMap<>();
        List<String> names = new ArrayList<>(bodies.keySet());

        for (int i = 0; i < names.size(); i++)
        {
            for (int j = i + 1; j < names.size(); j++)
            {
                String x = names.get(i);
                String y = names.get(j);
                String a = x.compareTo(y) <= 0 ? x : y;
                String b = x.compareTo(y) <= 0 ? y : x;
                Body ba = bodies.get(a);
                Body bb = bodies.get(b);

                Body.Contact contact = Body.contact(ba, bb);
                int depth = contact == null || contact.depth <= 0 ? 0 : (int) Math.round(contact.depth);
                Boolean mesh = null;
                PosedMesh.Hits hits = null;
                boolean decide = mode == RflConfig.ContactMode.MESH
                    || mode == RflConfig.ContactMode.CAPSULES_AND_MESH && depth >= START_DEPTH;
                boolean show = displayMesh && (mode == RflConfig.ContactMode.MESH || depth > 0);
                if (decide || show)
                {
                    long start = System.nanoTime();
                    if (ba.mesh != null && bb.mesh != null)
                    {
                        hits = PosedMesh.intersect(ba.mesh, bb.mesh,
                            mode == RflConfig.ContactMode.MESH ? null : capsuleRegions(ba, bb), maxMeshHits);
                    }
                    meshNanos += System.nanoTime() - start;
                    if (decide)
                    {
                        mesh = hits != null;
                    }
                }
                boolean touching = Boolean.TRUE.equals(mesh);
                boolean colliding = mode == RflConfig.ContactMode.CAPSULES ? depth >= START_DEPTH
                    : mode == RflConfig.ContactMode.CAPSULES_AND_MESH ? depth >= START_DEPTH && touching
                    : touching;
                if (contact != null && contact.depth > 0 || touching)
                {
                    pairs.put(pairKey(a, b), new Pair(contact, depth, mesh, hits, colliding));
                }
            }
        }

        return pairs;
    }

    /** Shared bounds of every overlapping capsule pair, grown by MESH_REGION_MARGIN. */
    private static List<float[]> capsuleRegions(Body a, Body b)
    {
        List<float[]> regions = new ArrayList<>();
        for (Capsule pa : a.parts)
        {
            for (Capsule pb : b.parts)
            {
                if (Capsule.penetration(pa, pb) > 0)
                {
                    float[] r = PosedMesh.overlap(bounds(pa), bounds(pb), MESH_REGION_MARGIN);
                    if (r != null)
                    {
                        regions.add(r);
                    }
                }
            }
        }
        return regions;
    }

    private static float[] bounds(Capsule c)
    {
        return new float[]{
            (float) (Math.min(c.ax, c.bx) - c.radius), (float) (Math.min(c.ay, c.by) - c.radius),
            (float) (Math.min(c.az, c.bz) - c.radius), (float) (Math.max(c.ax, c.bx) + c.radius),
            (float) (Math.max(c.ay, c.by) + c.radius), (float) (Math.max(c.az, c.bz) + c.radius)};
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
