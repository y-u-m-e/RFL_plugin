package com.rfl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import javax.inject.Singleton;

/**
 * This session's collisions and incompletes for the RFL panel: totals, the newest {@link #MAX_ROWS}
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
    private final Deque<CollisionLog.Incomplete> incompletes = new ArrayDeque<>();
    private int collisionCount;
    private int incompleteCount;
    /** A {@link Collision} or {@link CollisionLog.Incomplete}; null before the first event. */
    private Object latest;
    /** Bumped on every change, so the panel can refresh right away instead of waiting for its throttle. */
    private long version;

    /** A {@link Collision} or {@link CollisionLog.Incomplete}; anything else is ignored. */
    synchronized void onEvent(Object event)
    {
        if (event instanceof Collision)
        {
            collisions.addFirst((Collision) event);
            trim(collisions);
            collisionCount++;
        }
        else if (event instanceof CollisionLog.Incomplete)
        {
            incompletes.addFirst((CollisionLog.Incomplete) event);
            trim(incompletes);
            incompleteCount++;
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
        incompletes.clear();
        collisionCount = 0;
        incompleteCount = 0;
        latest = null;
        version++;
    }

    synchronized int collisionCount()
    {
        return collisionCount;
    }

    synchronized int incompleteCount()
    {
        return incompleteCount;
    }

    /** Newest first, at most {@link #MAX_ROWS}. */
    synchronized List<Collision> collisions()
    {
        return new ArrayList<>(collisions);
    }

    /** Newest first, at most {@link #MAX_ROWS}. */
    synchronized List<CollisionLog.Incomplete> incompletes()
    {
        return new ArrayList<>(incompletes);
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
