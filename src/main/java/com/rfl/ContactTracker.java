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
     * One overlapping pair from the latest update; {@code contact} once it passed START_DEPTH.
     * {@code partA}/{@code partB} name the deepest part pair (partA belongs to a).
     */
    static final class Overlap
    {
        final String a;
        final String b;
        final String partA;
        final String partB;
        final int depth;
        final boolean contact;

        Overlap(String a, String b, String partA, String partB, int depth, boolean contact)
        {
            this.a = a;
            this.b = b;
            this.partA = partA;
            this.partB = partB;
            this.depth = depth;
            this.contact = contact;
        }
    }

    // pairKey -> max overlap depth seen since the pair became active.
    private final Map<String, Integer> active = new HashMap<>();

    // pairKey -> deepest part pair in the latest update (every pair with penetration > 0).
    private Map<String, Body.Contact> latest = new HashMap<>();

    List<RflEvent> update(Map<String, Body> bodies, long now, int tick)
    {
        Map<String, Body.Contact> currentDepths = currentOverlaps(bodies);
        latest = currentDepths;
        List<RflEvent> events = new ArrayList<>();

        for (Map.Entry<String, Body.Contact> entry : currentDepths.entrySet())
        {
            String key = entry.getKey();
            int depth = depth(entry.getValue());
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
        for (Map.Entry<String, Body.Contact> entry : latest.entrySet())
        {
            String[] pair = splitKey(entry.getKey());
            Body.Contact c = entry.getValue();
            result.add(new Overlap(pair[0], pair[1], c.partA.name, c.partB.name, depth(c),
                active.containsKey(entry.getKey())));
        }
        return result;
    }

    /**
     * Name to the names they are colliding with in the latest update: overlap of at least
     * START_DEPTH right now. Unlike {@link #contactsByPlayer}, a contact being held while the
     * bodies separate does not count.
     */
    Map<String, List<String>> collidingNow()
    {
        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<String, Body.Contact> entry : latest.entrySet())
        {
            if (entry.getValue().depth < START_DEPTH)
            {
                continue;
            }
            String[] pair = splitKey(entry.getKey());
            result.computeIfAbsent(pair[0], k -> new ArrayList<>()).add(pair[1]);
            result.computeIfAbsent(pair[1], k -> new ArrayList<>()).add(pair[0]);
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

    private static int depth(Body.Contact contact)
    {
        return (int) Math.round(contact.depth);
    }

    private static Map<String, Body.Contact> currentOverlaps(Map<String, Body> bodies)
    {
        Map<String, Body.Contact> depths = new HashMap<>();
        List<String> names = new ArrayList<>(bodies.keySet());

        for (int i = 0; i < names.size(); i++)
        {
            for (int j = i + 1; j < names.size(); j++)
            {
                String x = names.get(i);
                String y = names.get(j);
                String a = x.compareTo(y) <= 0 ? x : y;
                String b = x.compareTo(y) <= 0 ? y : x;

                Body.Contact contact = Body.contact(bodies.get(a), bodies.get(b));
                if (contact != null && contact.depth > 0)
                {
                    depths.put(pairKey(a, b), contact);
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
