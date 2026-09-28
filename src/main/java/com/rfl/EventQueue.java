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
        final List<RflEvent> drained = new ArrayList<>(events);
        events.clear();
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
