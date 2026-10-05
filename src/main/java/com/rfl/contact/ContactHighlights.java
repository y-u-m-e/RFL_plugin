package com.rfl.contact;

import com.rfl.overlay.ContactHighlightOverlay;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.inject.Singleton;

/**
 * Recent contact points for the on-screen tile highlight. Written by {@link ContactDetector} when a
 * collision starts, read by {@link ContactHighlightOverlay}. Display only.
 */
@Singleton
public final class ContactHighlights
{
    public static final class Highlight
    {
        public final int x;
        public final int y;
        /** 1 when the contact starts, falling linearly to 0 at the end of the duration. */
        public final float alpha;
        /** Drawn in the incomplete colour instead of the contact colour. */
        public final boolean incomplete;

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
        public final int x;
        public final int y;
        final long startedAt;
        public final boolean incomplete;

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

    public synchronized void addIncomplete(int x, int y, long now)
    {
        entries.add(new Entry(x, y, now, true));
    }

    /** Live highlights with their fade, dropping any older than {@code durationMs}. */
    public synchronized List<Highlight> active(long now, long durationMs)
    {
        List<Highlight> live = new ArrayList<>();
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); )
        {
            Entry entry = it.next();
            long elapsed = now - entry.startedAt;
            if (durationMs <= 0 || elapsed >= durationMs)
            {
                it.remove();
                continue;
            }
            live.add(new Highlight(entry.x, entry.y, 1f - (float) elapsed / durationMs, entry.incomplete));
        }
        return live;
    }

    synchronized void clear()
    {
        entries.clear();
    }
}
