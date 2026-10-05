package com.rfl.panel;

import com.rfl.contact.Collision;
import com.rfl.incomplete.Incomplete;
import com.rfl.log.CollisionLog;
import sh.yumekui.toolkit.scene.SceneStamps;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Singleton;

/**
 * This session's collisions and incompletes for the RFL panel: totals, the newest {@link #MAX_ROWS}
 * of each (newest first), and the latest event of either kind. Fed by the {@link CollisionLog}
 * listener whether or not Save collisions is on; cleared on plugin start-up, so counts reset when
 * the plugin restarts, and each kind can be cleared from the panel (never the files on disk).
 *
 * <p>Threads: any; every method is synchronized and the getters return copies.
 */
@Singleton
public final class SessionEvents
{
    static final int MAX_ROWS = 50;

    private final Deque<Collision> collisions = new ArrayDeque<>();
    private final Deque<Incomplete> incompletes = new ArrayDeque<>();
    private int collisionCount;
    private int incompleteCount;
    /** A {@link Collision} or {@link Incomplete}; null before the first event. */
    private Object latest;
    /** Each kept event's {@link SceneStamps} stamp, so the panel can find its tile while it is still valid. */
    private final Map<Object, Integer> stamps = new IdentityHashMap<>();
    /** Bumped on every change, so the panel can refresh right away instead of waiting for its throttle. */
    private long version;

    /**
     * A {@link Collision} or {@link Incomplete}; anything else is ignored.
     *
     * @param stamp the {@link SceneStamps} stamp of the scene it happened in
     */
    public synchronized void onEvent(Object event, int stamp)
    {
        if (event instanceof Collision || event instanceof Incomplete)
        {
            stamps.put(event, stamp);
        }
        if (event instanceof Collision)
        {
            collisions.addFirst((Collision) event);
            trim(collisions);
            collisionCount++;
        }
        else if (event instanceof Incomplete)
        {
            incompletes.addFirst((Incomplete) event);
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

    private void trim(Deque<?> rows)
    {
        while (rows.size() > MAX_ROWS)
        {
            stamps.remove(rows.removeLast());
        }
    }

    /** The scene stamp an event arrived with, or {@link SceneStamps#NONE}. */
    synchronized int stamp(Object event)
    {
        Integer stamp = stamps.get(event);
        return stamp == null ? SceneStamps.NONE : stamp;
    }

    public synchronized void clear()
    {
        collisions.clear();
        incompletes.clear();
        stamps.clear();
        collisionCount = 0;
        incompleteCount = 0;
        latest = null;
        version++;
    }

    /**
     * The panel's Clear on the Collisions view: forgets this session's collisions and their count.
     * Nothing on disk changes. The latest event falls back to the newest incomplete, if any.
     */
    synchronized void clearCollisions()
    {
        for (Collision c : collisions)
        {
            stamps.remove(c);
        }
        collisions.clear();
        collisionCount = 0;
        if (latest instanceof Collision)
        {
            latest = incompletes.peekFirst();
        }
        version++;
    }

    /** The panel's Clear on the Incompletes view; as {@link #clearCollisions}, for incompletes. */
    synchronized void clearIncompletes()
    {
        for (Incomplete i : incompletes)
        {
            stamps.remove(i);
        }
        incompletes.clear();
        incompleteCount = 0;
        if (latest instanceof Incomplete)
        {
            latest = collisions.peekFirst();
        }
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
    synchronized List<Incomplete> incompletes()
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
