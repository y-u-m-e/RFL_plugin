package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class IncompleteDetectorTest
{
    private static final Map<String, List<String>> AMY_IN_CONTACT_WITH_ZED =
        Map.of("Amy", List.of("Zed"), "Zed", List.of("Amy"));

    @Test
    public void catchAfterAThrowWhileInContactIsAnIncomplete()
    {
        IncompleteDetector d = new IncompleteDetector();
        d.onTick(9, false, Set.of("Bo"), AMY_IN_CONTACT_WITH_ZED); // Bo holds it
        assertTrue(d.onTick(10, true, Set.of(), AMY_IN_CONTACT_WITH_ZED).isEmpty()); // thrown
        List<IncompleteDetector.Incomplete> found =
            d.onTick(11, false, Set.of("Amy"), AMY_IN_CONTACT_WITH_ZED); // gone, in Amy's hand
        assertEquals(1, found.size());
        assertEquals("Amy", found.get(0).receiver);
        assertEquals(List.of("Zed"), found.get(0).contacts);
    }

    @Test
    public void catcherHoldingWhileTheProjectileIsStillDrawnCountsOnceItDisappears()
    {
        IncompleteDetector d = new IncompleteDetector();
        d.onTick(9, false, Set.of("Bo"), AMY_IN_CONTACT_WITH_ZED);
        d.onTick(10, true, Set.of(), AMY_IN_CONTACT_WITH_ZED);
        // Same tick the ball reaches Amy's hand, the projectile is still drawn.
        assertTrue(d.onTick(11, true, Set.of("Amy"), AMY_IN_CONTACT_WITH_ZED).isEmpty());
        assertEquals(1, d.onTick(12, false, Set.of("Amy"), AMY_IN_CONTACT_WITH_ZED).size());
    }

    @Test
    public void holdingOnlyAfterTheBallDisappearedIsNotAnIncomplete()
    {
        // The check happens on the tick the projectile stops being drawn; nobody holds it then.
        IncompleteDetector d = new IncompleteDetector();
        d.onTick(10, true, Set.of(), AMY_IN_CONTACT_WITH_ZED);
        assertTrue(d.onTick(11, false, Set.of(), AMY_IN_CONTACT_WITH_ZED).isEmpty());
        assertTrue(d.onTick(12, false, Set.of("Amy"), AMY_IN_CONTACT_WITH_ZED).isEmpty());
    }

    @Test
    public void collisionThatEndedBeforeTheBallDisappearedDoesNotCount()
    {
        IncompleteDetector d = new IncompleteDetector();
        d.onTick(10, true, Set.of(), AMY_IN_CONTACT_WITH_ZED); // colliding while it flies
        d.onTick(11, true, Set.of("Amy"), AMY_IN_CONTACT_WITH_ZED);
        assertTrue(d.onTick(12, false, Set.of("Amy"), Collections.emptyMap()).isEmpty()); // apart when it lands
    }

    @Test
    public void uncontestedCatchIsNotAnIncomplete()
    {
        IncompleteDetector d = new IncompleteDetector();
        d.onTick(10, true, Set.of(), Collections.emptyMap());
        assertTrue(d.onTick(11, false, Set.of("Amy"), Collections.emptyMap()).isEmpty());
    }

    @Test
    public void handoffWithoutAThrowIsNotAnIncomplete()
    {
        IncompleteDetector d = new IncompleteDetector();
        d.onTick(10, false, Set.of("Zed"), AMY_IN_CONTACT_WITH_ZED);
        assertTrue(d.onTick(11, false, Set.of("Amy"), AMY_IN_CONTACT_WITH_ZED).isEmpty());
    }

    @Test
    public void ballStillInTheAirIsNotYetACatch()
    {
        IncompleteDetector d = new IncompleteDetector();
        d.onTick(10, true, Set.of(), AMY_IN_CONTACT_WITH_ZED);
        assertTrue(d.onTick(11, true, Set.of("Amy"), AMY_IN_CONTACT_WITH_ZED).isEmpty());
    }


    @Test
    public void someoneHoldingBeforeTheThrowIsNotTheCatcher()
    {
        IncompleteDetector d = new IncompleteDetector();
        d.onTick(9, false, Set.of("Amy"), AMY_IN_CONTACT_WITH_ZED);
        d.onTick(10, true, Set.of("Amy"), AMY_IN_CONTACT_WITH_ZED);
        assertTrue(d.onTick(11, false, Set.of("Amy"), AMY_IN_CONTACT_WITH_ZED).isEmpty());
    }

    @Test
    public void aThrowIsCaughtOnlyOnce()
    {
        IncompleteDetector d = new IncompleteDetector();
        d.onTick(10, true, Set.of(), AMY_IN_CONTACT_WITH_ZED);
        assertEquals(1, d.onTick(11, false, Set.of("Amy"), AMY_IN_CONTACT_WITH_ZED).size());
        assertTrue(d.onTick(12, false, Set.of("Amy"), AMY_IN_CONTACT_WITH_ZED).isEmpty());
    }
}
