package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
        Box p = new Box(0, 100, 0, 100, 0, 100);

        List<RflEvent> e1 = t.update(Map.of("Zed", p, "Amy", new Box(80, 180, 0, 100, 0, 100)), 1000, 1);
        assertEquals("contact_start", e1.get(0).type);
        assertEquals("Amy", e1.get(0).a);
        assertEquals(20, (int) e1.get(0).depth);

        assertTrue(t.update(Map.of("Zed", p, "Amy", new Box(50, 150, 0, 100, 0, 100)), 1020, 1).isEmpty());

        List<RflEvent> e3 = t.update(Map.of("Zed", p, "Amy", new Box(300, 400, 0, 100, 0, 100)), 1040, 2);
        assertEquals("contact_end", e3.get(0).type);
        assertEquals(50, (int) e3.get(0).depth);
    }

    @Test
    public void playerLeavingViewEndsContact()
    {
        ContactTracker t = new ContactTracker();
        t.update(Map.of("A", new Box(0, 100, 0, 100, 0, 100), "B", new Box(50, 150, 0, 100, 0, 100)), 0, 0);
        assertEquals("contact_end", t.update(Map.of("A", new Box(0, 100, 0, 100, 0, 100)), 20, 0).get(0).type);
    }

    @Test
    public void pairOrderIndependentOfInsertion()
    {
        Box a = new Box(0, 100, 0, 100, 0, 100);
        Box b = new Box(50, 150, 0, 100, 0, 100);

        Map<String, Box> insertBFirst = new LinkedHashMap<>();
        insertBFirst.put("Zed", b);
        insertBFirst.put("Amy", a);
        RflEvent fromBFirst = new ContactTracker().update(insertBFirst, 0, 0).get(0);

        Map<String, Box> insertAFirst = new LinkedHashMap<>();
        insertAFirst.put("Amy", a);
        insertAFirst.put("Zed", b);
        RflEvent fromAFirst = new ContactTracker().update(insertAFirst, 0, 0).get(0);

        assertEquals("Amy", fromBFirst.a);
        assertEquals("Zed", fromBFirst.b);
        assertEquals(fromAFirst.a, fromBFirst.a);
        assertEquals(fromAFirst.b, fromBFirst.b);
    }
}
