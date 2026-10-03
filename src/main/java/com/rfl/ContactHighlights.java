package com.rfl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.inject.Singleton;

/**
 * Recent contact points for the on-screen tile highlight. Written by {@link ContactDetector} when a
 * collision starts, read by {@link ContactHighlightOverlay}. Display only.
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
        /** Drawn in the incomplete colour instead of the contact colour. */
        final boolean incomplete;

        Highlight(int x, int y, float alpha, boolean incomplete)
        {
            this.x = x;
            this.y = y;
            this.alpha = alpha;
            this.incomplete = incomplete;
        }
    }

    private static final class Entry
    {
        final int x;
        final int y;
        final long startedAt;
        final boolean incomplete;

        Entry(int x, int y, long startedAt, boolean incomplete)
        {
            this.x = x;
            this.y = y;
            this.startedAt = startedAt;
            this.incomplete = incomplete;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    synchronized void add(int x, int y, long now)
    {
        entries.add(new Entry(x, y, now, false));
    }

    synchronized void addIncomplete(int x, int y, long now)
    {
        entries.add(new Entry(x, y, now, true));
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
            live.add(new Highlight(e.x, e.y, 1f - (float) elapsed / durationMs, e.incomplete));
        }
        return live;
    }

    synchronized void clear()
    {
        entries.clear();
    }
}
