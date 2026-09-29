package com.rfl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.inject.Singleton;

/**
 * Recent contact points for the on-screen tile highlight. Written by {@link ContactDetector} when a
 * contact starts, read by {@link ContactHighlightOverlay}. Display only; nothing here is reported.
 */
@Singleton
final class ContactHighlights
{
    static final class Highlight
    {
        final int x;
        final int y;
        /** 1 when the contact starts, falling linearly to 0 at the end of the duration. */
        final float alpha;

        Highlight(int x, int y, float alpha)
        {
            this.x = x;
            this.y = y;
            this.alpha = alpha;
        }
    }

    private static final class Entry
    {
        final int x;
        final int y;
        final long startedAt;

        Entry(int x, int y, long startedAt)
        {
            this.x = x;
            this.y = y;
            this.startedAt = startedAt;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    synchronized void add(int x, int y, long now)
    {
        entries.add(new Entry(x, y, now));
    }

    /** Live highlights with their fade, dropping any older than {@code durationMs}. */
    synchronized List<Highlight> active(long now, long durationMs)
    {
        List<Highlight> live = new ArrayList<>();
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); )
        {
            Entry e = it.next();
            long elapsed = now - e.startedAt;
            if (durationMs <= 0 || elapsed >= durationMs)
            {
                it.remove();
                continue;
            }
            live.add(new Highlight(e.x, e.y, 1f - (float) elapsed / durationMs));
        }
        return live;
    }

    synchronized void clear()
    {
        entries.clear();
    }
}
