package com.rfl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pair state machine over per-frame player meshes (see {@link PosedMesh}). A contact starts on the
 * first update any triangles of the two meshes touch and ends on the first update none do. Its
 * depth is the number of touching triangle pairs (capped at {@link PosedMesh#MAX_HITS}); the
 * contact_end carries the most seen during the contact.
 *
 * Names are expected to already be {@code Text.sanitize}d by the caller so two clients derive the
 * identical pair key regardless of non-breaking spaces in the raw RSN. Pairwise over all keys: at
 * the Hub's own render-distance player cap this is at most 45 pairs, and each pair's whole-mesh
 * bounds are checked before any triangles.
 */
final class ContactTracker
{
    /**
     * One pair from the latest update whose whole-mesh bounds overlap. {@code triangles} is the
     * number of touching triangle pairs: above 0 means a contact.
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

    // pairKey -> max touching triangle count seen since the pair became active.
    private final Map<String, Integer> active = new HashMap<>();

    // pairKey -> touching triangles in the latest update (null: bounds overlap, no triangles touch).
    private Map<String, PosedMesh.Hits> latest = new HashMap<>();

    private long meshNanos;

    List<RflEvent> update(Map<String, PosedMesh> meshes, long now, int tick)
    {
        latest = currentPairs(meshes);
        List<RflEvent> events = new ArrayList<>();

        for (Map.Entry<String, PosedMesh.Hits> entry : latest.entrySet())
        {
            PosedMesh.Hits hits = entry.getValue();
            if (hits == null)
            {
                continue;
            }
            String key = entry.getKey();
            Integer maxSoFar = active.get(key);
            if (maxSoFar == null)
            {
                String[] names = splitKey(key);
                events.add(RflEvent.contactStart(now, tick, names[0], names[1], hits.count));
                active.put(key, hits.count);
            }
            else if (hits.count > maxSoFar)
            {
                active.put(key, hits.count);
            }
        }

        List<String> ended = new ArrayList<>();
        for (String key : active.keySet())
        {
            if (latest.get(key) == null)
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

    private Map<String, PosedMesh.Hits> currentPairs(Map<String, PosedMesh> meshes)
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
                String a = x.compareTo(y) <= 0 ? x : y;
                String b = x.compareTo(y) <= 0 ? y : x;
                PosedMesh ma = meshes.get(a);
                PosedMesh mb = meshes.get(b);
                if (PosedMesh.overlap(ma.bounds, mb.bounds) != null)
                {
                    pairs.put(pairKey(a, b), PosedMesh.intersect(ma, mb));
                }
            }
        }

        meshNanos += System.nanoTime() - start;
        return pairs;
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
