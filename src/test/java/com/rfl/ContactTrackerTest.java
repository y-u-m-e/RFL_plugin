package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ContactTrackerTest
{
    @Test
    public void startThenEndCarriesMaxDepth()
    {
        ContactTracker t = new ContactTracker();
        Cylinder p = new Cylinder(50, 50, 50, 0, 100);

        List<RflEvent> e1 = t.update(Map.of("Zed", p, "Amy", new Cylinder(130, 50, 50, 0, 100)), 1000, 1);
        assertEquals("contact_start", e1.get(0).type);
        assertEquals("Amy", e1.get(0).a);
        assertEquals(20, (int) e1.get(0).depth);

        assertTrue(t.update(Map.of("Zed", p, "Amy", new Cylinder(100, 50, 50, 0, 100)), 1020, 1).isEmpty());

        List<RflEvent> e3 = t.update(Map.of("Zed", p, "Amy", new Cylinder(350, 50, 50, 0, 100)), 1040, 2);
        assertEquals("contact_end", e3.get(0).type);
        assertEquals(50, (int) e3.get(0).depth);
    }

    @Test
    public void playerLeavingViewEndsContact()
    {
        ContactTracker t = new ContactTracker();
        t.update(Map.of("A", new Cylinder(50, 50, 50, 0, 100), "B", new Cylinder(100, 50, 50, 0, 100)), 0, 0);
        assertEquals("contact_end", t.update(Map.of("A", new Cylinder(50, 50, 50, 0, 100)), 20, 0).get(0).type);
    }

    @Test
    public void pairOrderIndependentOfInsertion()
    {
        Cylinder a = new Cylinder(50, 50, 50, 0, 100);
        Cylinder b = new Cylinder(100, 50, 50, 0, 100);

        Map<String, Cylinder> insertBFirst = new LinkedHashMap<>();
        insertBFirst.put("Zed", b);
        insertBFirst.put("Amy", a);
        RflEvent fromBFirst = new ContactTracker().update(insertBFirst, 0, 0).get(0);

        Map<String, Cylinder> insertAFirst = new LinkedHashMap<>();
        insertAFirst.put("Amy", a);
        insertAFirst.put("Zed", b);
        RflEvent fromAFirst = new ContactTracker().update(insertAFirst, 0, 0).get(0);

        assertEquals("Amy", fromBFirst.a);
        assertEquals("Zed", fromBFirst.b);
        assertEquals(fromAFirst.a, fromBFirst.a);
        assertEquals(fromAFirst.b, fromBFirst.b);
    }

    @Test
    public void emptyMapClosesEveryOpenPairAndResetAllowsFreshStart()
    {
        ContactTracker t = new ContactTracker();

        // A overlaps both B and C; B and C don't overlap each other, so two pairs are open.
        Cylinder wide = new Cylinder(100, 50, 100, 0, 100);
        Cylinder left = new Cylinder(0, 50, 50, 0, 100);
        Cylinder right = new Cylinder(200, 50, 50, 0, 100);

        List<RflEvent> started = t.update(Map.of("A", wide, "B", left, "C", right), 0, 0);
        assertEquals(2, started.size());
        assertTrue(started.stream().allMatch(e -> "contact_start".equals(e.type)));

        List<RflEvent> ended = t.update(Collections.emptyMap(), 20, 1);
        assertEquals("update(emptyMap) should close every open pair, one contact_end each", 2, ended.size());
        assertTrue(ended.stream().allMatch(e -> "contact_end".equals(e.type)));

        // Same overlap after the pairs already closed re-fires immediately (active is empty) --
        // reset() only matters when a pair is still open. Start one again without closing it,
        // then prove reset() (not another update) is what clears it for a fresh start.
        List<RflEvent> restartedBeforeReset = t.update(Map.of("A", wide, "B", left), 40, 2);
        assertEquals(1, restartedBeforeReset.size());
        assertEquals("contact_start", restartedBeforeReset.get(0).type);

        t.reset();

        List<RflEvent> restartedAfterReset = t.update(Map.of("A", wide, "B", left), 60, 3);
        assertEquals("reset() should let the same still-overlapping pair start fresh", 1, restartedAfterReset.size());
        assertEquals("contact_start", restartedAfterReset.get(0).type);
    }
}
