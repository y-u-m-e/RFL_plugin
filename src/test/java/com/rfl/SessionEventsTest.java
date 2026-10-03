package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.List;

import org.junit.Test;

/** {@link SessionEvents}: this session's counts, capped newest-first rows, and the latest event. */
public class SessionEventsTest
{
    private static Collision collision(int n)
    {
        return new Collision("A" + n, "B", List.of("B"), n, n + 1, n, n, 330, 0, 0, 0, 1);
    }

    @Test
    public void countsAllButKeepsTheNewestFifty()
    {
        SessionEvents s = new SessionEvents();
        for (int i = 0; i < 60; i++)
        {
            s.onEvent(collision(i));
        }
        assertEquals(60, s.collisionCount());
        assertEquals(SessionEvents.MAX_ROWS, s.collisions().size());
        assertEquals("A59", s.collisions().get(0).a);
        assertEquals("A10", s.collisions().get(SessionEvents.MAX_ROWS - 1).a);
    }

    @Test
    public void latestIsEitherKindAndClearResets()
    {
        SessionEvents s = new SessionEvents();
        assertNull(s.latest());
        long v0 = s.version();
        Collision c = collision(1);
        s.onEvent(c);
        assertSame(c, s.latest());
        CollisionLog.Incomplete i = new CollisionLog.Incomplete("Bob", List.of("Amy"), 5, 1, 330, 0, 0, 0);
        s.onEvent(i);
        assertSame(i, s.latest());
        assertEquals(1, s.incompleteCount());
        assertEquals(List.of(i), s.incompletes());
        assertNotEquals(v0, s.version());

        s.onEvent("ignored");
        assertSame(i, s.latest());

        s.clear();
        assertEquals(0, s.collisionCount());
        assertEquals(0, s.incompleteCount());
        assertNull(s.latest());
        assertEquals(List.of(), s.collisions());
    }
}
