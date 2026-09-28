package com.rfl;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Holds events between report sends. Caps at 5 minutes of events (relative to the newest one
 * queued) so a stalled sender can't grow this without bound; the server's {@code gap} event
 * covers whatever gets dropped beyond that window (spec §3 "Report sending").
 */
@Singleton
final class EventQueue
{
    private static final long MAX_AGE_MS = 300_000;

    /**
     * Most events sent in one report. The server 400s (and the sender then drops) any batch over
     * 500 events or 64 KB, so a backlog after an outage goes out in slices instead of being lost.
     */
    static final int MAX_BATCH = 400;

    private final List<RflEvent> events = new ArrayList<>();

    @Inject
    EventQueue()
    {
    }

    synchronized void add(final RflEvent event)
    {
        events.add(event);
        dropStale();
    }

    synchronized List<RflEvent> drain()
    {
        return drain(Integer.MAX_VALUE);
    }

    /**
     * Removes and returns up to {@code max} of the oldest queued events; the rest stay queued.
     *
     * @param max most events to return
     * @return drained events, oldest-first
     */
    synchronized List<RflEvent> drain(final int max)
    {
        final List<RflEvent> head = events.subList(0, Math.min(max, events.size()));
        final List<RflEvent> drained = new ArrayList<>(head);
        head.clear();
        return drained;
    }

    /**
     * Puts previously drained events back at the front of the queue, ahead of anything added
     * since (used when a batch needs to be retried after a failed send).
     *
     * @param drained events to put back, oldest-first
     */
    synchronized void requeue(final List<RflEvent> drained)
    {
        events.addAll(0, drained);
        dropStale();
    }

    private void dropStale()
    {
        long newestAt = Long.MIN_VALUE;
        for (final RflEvent event : events)
        {
            newestAt = Math.max(newestAt, event.at);
        }

        final long cutoff = newestAt - MAX_AGE_MS;
        events.removeIf(event -> event.at < cutoff);
    }
}
