package com.rfl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pair state machine over per-tick player boxes. Names are expected to already be
 * {@code Text.sanitize}d by the caller (Task 4) so two clients derive the identical pair key
 * regardless of non-breaking spaces in the raw RSN.
 *
 * Pairwise over all keys — at the Hub's own render-distance player cap this is at most 45
 * pairs, so no broad-phase spatial index is needed.
 */
final class ContactTracker
{
    // pairKey -> max overlap depth seen since the pair became active.
    private final Map<String, Integer> active = new HashMap<>();

    List<RflEvent> update(Map<String, Box> boxes, long now, int tick)
    {
        Map<String, Integer> currentDepths = currentOverlaps(boxes);
        List<RflEvent> events = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : currentDepths.entrySet())
        {
            String key = entry.getKey();
            int depth = entry.getValue();
            Integer maxSoFar = active.get(key);

            if (maxSoFar == null)
            {
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
    }

    private static Map<String, Integer> currentOverlaps(Map<String, Box> boxes)
    {
        Map<String, Integer> depths = new HashMap<>();
        List<String> names = new ArrayList<>(boxes.keySet());

        for (int i = 0; i < names.size(); i++)
        {
            for (int j = i + 1; j < names.size(); j++)
            {
                String x = names.get(i);
                String y = names.get(j);
                String a = x.compareTo(y) <= 0 ? x : y;
                String b = x.compareTo(y) <= 0 ? y : x;

                int depth = Box.overlapDepth(boxes.get(a), boxes.get(b));
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
