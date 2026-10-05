package com.rfl.panel;

import com.rfl.Fixtures;
import com.rfl.contact.Collision;
import com.rfl.incomplete.Incomplete;
import sh.yumekui.toolkit.scene.SceneStamps;

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
        return Fixtures.collision("A" + n, "B").at(n, 1).build();
    }

    @Test
    public void countsAllButKeepsTheNewestFifty()
    {
        SessionEvents s = new SessionEvents();
        for (int i = 0; i < 60; i++)
        {
            s.onEvent(collision(i), SceneStamps.NONE);
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
        s.onEvent(c, SceneStamps.NONE);
        assertSame(c, s.latest());
        Incomplete i = Fixtures.incomplete("Bob").contacts("Amy").at(5, 1).build();
        s.onEvent(i, SceneStamps.NONE);
        assertSame(i, s.latest());
        assertEquals(1, s.incompleteCount());
        assertEquals(List.of(i), s.incompletes());
        assertNotEquals(v0, s.version());

        s.onEvent("ignored", SceneStamps.NONE);
        assertSame(i, s.latest());

        s.clear();
        assertEquals(0, s.collisionCount());
        assertEquals(0, s.incompleteCount());
        assertNull(s.latest());
        assertEquals(List.of(), s.collisions());
    }

    @Test
    public void clearCollisionsKeepsIncompletesAndFallsBackToTheNewestOne()
    {
        SessionEvents s = new SessionEvents();
        Incomplete i = Fixtures.incomplete("Bob").contacts("Amy").at(5, 1).build();
        s.onEvent(i, SceneStamps.NONE);
        Collision c = collision(7);
        s.onEvent(c, SceneStamps.NONE);
        long v = s.version();

        s.clearCollisions();
        assertEquals(0, s.collisionCount());
        assertEquals(List.of(), s.collisions());
        assertEquals(1, s.incompleteCount());
        assertSame("latest falls back to the incomplete", i, s.latest());
        assertNotEquals(v, s.version());

        s.clearIncompletes();
        assertEquals(0, s.incompleteCount());
        assertNull(s.latest());
    }

    @Test
    public void clearIncompletesKeepsALatestCollision()
    {
        SessionEvents s = new SessionEvents();
        s.onEvent(Fixtures.incomplete("Bob").contacts("Amy").at(5, 1).build(), SceneStamps.NONE);
        Collision c = collision(7);
        s.onEvent(c, SceneStamps.NONE);
        s.clearIncompletes();
        assertSame(c, s.latest());
        assertEquals(1, s.collisionCount());
    }
}
