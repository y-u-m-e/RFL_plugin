package com.rfl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import javax.inject.Singleton;

/**
 * This session's collisions and interceptions for the RFL panel: totals, the newest {@link #MAX_ROWS}
 * of each (newest first), and the latest event of either kind. Fed by the {@link CollisionLog}
 * listener whether or not Save collisions is on; cleared on plugin start-up, so counts reset when
 * the plugin restarts.
 *
 * <p>Threads: any; every method is synchronized and the getters return copies.
 */
@Singleton
final class SessionEvents
{
    static final int MAX_ROWS = 50;

    private final Deque<Collision> collisions = new ArrayDeque<>();
    private final Deque<CollisionLog.Interception> interceptions = new ArrayDeque<>();
    private int collisionCount;
    private int interceptionCount;
    /** A {@link Collision} or {@link CollisionLog.Interception}; null before the first event. */
    private Object latest;
    /** Bumped on every change, so the panel can refresh right away instead of waiting for its throttle. */
    private long version;

    /** A {@link Collision} or {@link CollisionLog.Interception}; anything else is ignored. */
    synchronized void onEvent(Object event)
    {
        if (event instanceof Collision)
        {
            collisions.addFirst((Collision) event);
            trim(collisions);
            collisionCount++;
        }
        else if (event instanceof CollisionLog.Interception)
        {
            interceptions.addFirst((CollisionLog.Interception) event);
            trim(interceptions);
            interceptionCount++;
        }
        else
        {
            return;
        }
        latest = event;
        version++;
    }

    private static void trim(Deque<?> rows)
    {
        while (rows.size() > MAX_ROWS)
        {
            rows.removeLast();
        }
    }

    synchronized void clear()
    {
        collisions.clear();
        interceptions.clear();
        collisionCount = 0;
        interceptionCount = 0;
        latest = null;
        version++;
    }

    synchronized int collisionCount()
    {
        return collisionCount;
    }

    synchronized int interceptionCount()
    {
        return interceptionCount;
    }

    /** Newest first, at most {@link #MAX_ROWS}. */
    synchronized List<Collision> collisions()
    {
        return new ArrayList<>(collisions);
    }

    /** Newest first, at most {@link #MAX_ROWS}. */
    synchronized List<CollisionLog.Interception> interceptions()
    {
        return new ArrayList<>(interceptions);
    }

    synchronized Object latest()
    {
        return latest;
    }

    synchronized long version()
    {
        return version;
    }
}
