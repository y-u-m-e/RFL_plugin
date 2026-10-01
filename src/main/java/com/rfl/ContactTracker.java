package com.rfl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pair state machine over per-tick player bodies. Names are expected to already be
 * {@code Text.sanitize}d by the caller (Task 4) so two clients derive the identical pair key
 * regardless of non-breaking spaces in the raw RSN.
 *
 * Pairwise over all keys — at the Hub's own render-distance player cap this is at most 45
 * pairs, so no broad-phase spatial index is needed.
 */
final class ContactTracker
{
    /**
     * Overlap (local units, 128 = one tile) a pair must reach before it counts as a contact.
     * Measured in game (2026-09-29): edge cases peak at depth 16-27, real contacts at 81-107, with
     * nothing between. Once started, a contact holds until the bodies fully separate, so it
     * doesn't flicker around the threshold.
     */
    static final int START_DEPTH = 40;

    /** One overlapping pair from the latest update; {@code contact} once it passed START_DEPTH. */
    static final class Overlap
    {
        final String a;
        final String b;
        final int depth;
        final boolean contact;

        Overlap(String a, String b, int depth, boolean contact)
        {
            this.a = a;
            this.b = b;
            this.depth = depth;
            this.contact = contact;
        }
    }

    // pairKey -> max overlap depth seen since the pair became active.
    private final Map<String, Integer> active = new HashMap<>();

    // pairKey -> overlap depth in the latest update (every pair with depth > 0).
    private Map<String, Integer> latest = new HashMap<>();

    List<RflEvent> update(Map<String, Cylinder> bodies, long now, int tick)
    {
        Map<String, Integer> currentDepths = currentOverlaps(bodies);
        latest = currentDepths;
        List<RflEvent> events = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : currentDepths.entrySet())
        {
            String key = entry.getKey();
            int depth = entry.getValue();
            Integer maxSoFar = active.get(key);

            if (maxSoFar == null)
            {
                if (depth < START_DEPTH)
                {
                    continue;
                }
                String[] pair = splitKey(key);
                events.add(RflEvent.contactStart(now, tick, pair[0], pair[1], depth));
                active.put(key, depth);
            }
            else if (depth > maxSoFar)
            {
                active.put(key, depth);
            }
        }

        List<String> ended = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : active.entrySet())
        {
            if (!currentDepths.containsKey(entry.getKey()))
            {
                ended.add(entry.getKey());
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

    /** Every overlapping pair from the latest update, contacts and sub-threshold grazes alike. */
    List<Overlap> overlaps()
    {
        List<Overlap> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : latest.entrySet())
        {
            String[] pair = splitKey(entry.getKey());
            result.add(new Overlap(pair[0], pair[1], entry.getValue(), active.containsKey(entry.getKey())));
        }
        return result;
    }

    /** Name to the names they are currently in contact with (pairs past START_DEPTH). */
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

    private static Map<String, Integer> currentOverlaps(Map<String, Cylinder> bodies)
    {
        Map<String, Integer> depths = new HashMap<>();
        List<String> names = new ArrayList<>(bodies.keySet());

        for (int i = 0; i < names.size(); i++)
        {
            for (int j = i + 1; j < names.size(); j++)
            {
                String x = names.get(i);
                String y = names.get(j);
                String a = x.compareTo(y) <= 0 ? x : y;
                String b = x.compareTo(y) <= 0 ? y : x;

                int depth = Cylinder.overlapDepth(bodies.get(a), bodies.get(b));
                if (depth > 0)
                {
                    depths.put(pairKey(a, b), depth);
                }
            }
        }

        return depths;
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
