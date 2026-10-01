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
    /** One upright part, like the old whole-body cylinder: two of these at distance d overlap by 2r - d. */
    private static Body upright(int x, int y, int r)
    {
        return new Body(List.of(new Capsule("torso", x, y, 0, x, y, 100, r)), x, y);
    }

    @Test
    public void startThenEndCarriesMaxDepth()
    {
        ContactTracker t = new ContactTracker();
        Body p = upright(50, 50, 50);

        List<RflEvent> e1 = t.update(Map.of("Zed", p, "Amy", upright(110, 50, 50)), 1000, 1);
        assertEquals("contact_start", e1.get(0).type);
        assertEquals("Amy", e1.get(0).a);
        assertEquals(40, (int) e1.get(0).depth);

        assertTrue(t.update(Map.of("Zed", p, "Amy", upright(100, 50, 50)), 1020, 1).isEmpty());

        List<RflEvent> e3 = t.update(Map.of("Zed", p, "Amy", upright(350, 50, 50)), 1040, 2);
        assertEquals("contact_end", e3.get(0).type);
        assertEquals(50, (int) e3.get(0).depth);
    }

    @Test
    public void playerLeavingViewEndsContact()
    {
        ContactTracker t = new ContactTracker();
        t.update(Map.of("A", upright(50, 50, 50), "B", upright(100, 50, 50)), 0, 0);
        assertEquals("contact_end", t.update(Map.of("A", upright(50, 50, 50)), 20, 0).get(0).type);
    }

    @Test
    public void pairOrderIndependentOfInsertion()
    {
        Body a = upright(50, 50, 50);
        Body b = upright(100, 50, 50);

        Map<String, Body> insertBFirst = new LinkedHashMap<>();
        insertBFirst.put("Zed", b);
        insertBFirst.put("Amy", a);
        RflEvent fromBFirst = new ContactTracker().update(insertBFirst, 0, 0).get(0);

        Map<String, Body> insertAFirst = new LinkedHashMap<>();
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
        Body wide = upright(100, 50, 100);
        Body left = upright(0, 50, 50);
        Body right = upright(200, 50, 50);

        List<RflEvent> started = t.update(Map.of("A", wide, "B", left, "C", right), 0, 0);
        assertEquals(2, started.size());
        assertTrue(started.stream().allMatch(e -> "contact_start".equals(e.type)));

        List<RflEvent> ended = t.update(Collections.emptyMap(), 20, 1);
        assertEquals("update(emptyMap) should close every open pair, one contact_end each", 2, ended.size());
        assertTrue(ended.stream().allMatch(e -> "contact_end".equals(e.type)));

        List<RflEvent> restartedBeforeReset = t.update(Map.of("A", wide, "B", left), 40, 2);
        assertEquals(1, restartedBeforeReset.size());
        assertEquals("contact_start", restartedBeforeReset.get(0).type);

        t.reset();

        List<RflEvent> restartedAfterReset = t.update(Map.of("A", wide, "B", left), 60, 3);
        assertEquals("reset() should let the same still-overlapping pair start fresh", 1, restartedAfterReset.size());
        assertEquals("contact_start", restartedAfterReset.get(0).type);
    }

    @Test
    public void grazesBelowTheStartDepthAreNotContactsButAStartedContactHoldsUntilSeparated()
    {
        ContactTracker t = new ContactTracker();
        Body amy = upright(0, 0, 50);
        int start = ContactTracker.START_DEPTH;

        // Just under the start depth: a graze, no contact.
        assertTrue(t.update(Map.of("Amy", amy, "Zed", upright(100 - start + 1, 0, 50)), 0, 0).isEmpty());

        // Exactly the start depth: contact starts.
        List<RflEvent> started = t.update(Map.of("Amy", amy, "Zed", upright(100 - start, 0, 50)), 20, 1);
        assertEquals("contact_start", started.get(0).type);

        // Back to overlap 5: still the same contact, no flicker.
        assertTrue(t.update(Map.of("Amy", amy, "Zed", upright(95, 0, 50)), 40, 1).isEmpty());

        // Fully apart: ends, carrying the max depth.
        List<RflEvent> end = t.update(Map.of("Amy", amy, "Zed", upright(120, 0, 50)), 60, 2);
        assertEquals("contact_end", end.get(0).type);
        assertEquals(start, (int) end.get(0).depth);
    }

    @Test
    public void exposesOverlapsAndWhoIsInContact()
    {
        ContactTracker t = new ContactTracker();
        Body amy = upright(0, 0, 50);
        // Zed overlaps Amy by 50 (contact); Bo overlaps Amy by 10 (graze).
        t.update(Map.of("Amy", amy, "Zed", upright(50, 0, 50), "Bo", upright(0, 90, 50)), 0, 0);

        List<ContactTracker.Overlap> overlaps = t.overlaps();
        assertEquals(2, overlaps.size());
        assertTrue(overlaps.stream().anyMatch(o -> o.a.equals("Amy") && o.b.equals("Zed") && o.contact && o.depth == 50));
        assertTrue(overlaps.stream().anyMatch(o -> o.a.equals("Amy") && o.b.equals("Bo") && !o.contact && o.depth == 10));

        assertEquals(List.of("Zed"), t.contactsByPlayer().get("Amy"));
        assertEquals(List.of("Amy"), t.contactsByPlayer().get("Zed"));
        assertEquals(null, t.contactsByPlayer().get("Bo"));
    }

    /** Torso at x, plus (for Amy) an arm reaching from x+20 to x+80 at shoulder height. */
    private static Body withArm(int x)
    {
        return new Body(List.of(
            new Capsule("torso", x, 0, 100, x, 0, 160, 15),
            new Capsule("rightForearm", x + 20, 0, 150, x + 80, 0, 150, 10)), x, 0);
    }

    private static Body torsoOnly(int x)
    {
        return new Body(List.of(new Capsule("torso", x, 0, 100, x, 0, 160, 15)), x, 0);
    }

    @Test
    public void armReachingNearATorsoIsOnlyAContactPastTheStartDepth()
    {
        // Torsos are 100 / 90 apart (no overlap); only Amy's arm reaches Zed's torso.
        // Arm tip to Zed's axis 20 -> penetration 25 - 20 = 5: a graze.
        ContactTracker t = new ContactTracker();
        assertTrue(t.update(Map.of("Amy", withArm(0), "Zed", torsoOnly(100)), 0, 0).isEmpty());
        ContactTracker.Overlap graze = t.overlaps().get(0);
        assertEquals(5, graze.depth);
        assertEquals(false, graze.contact);
        assertEquals("rightForearm", graze.partA);
        assertEquals("torso", graze.partB);

        // Arm tip 10 from the axis -> penetration 15 >= START_DEPTH: a contact.
        List<RflEvent> start = t.update(Map.of("Amy", withArm(0), "Zed", torsoOnly(90)), 20, 1);
        assertTrue(15 >= ContactTracker.START_DEPTH);
        assertEquals("contact_start", start.get(0).type);
        assertEquals(15, (int) start.get(0).depth);
    }

    @Test
    public void collidingNowNeedsStartDepthThisFrameNotJustAHeldContact()
    {
        ContactTracker t = new ContactTracker();
        int deep = ContactTracker.START_DEPTH + 10;
        t.update(Map.of("Amy", upright(0, 0, 50), "Zed", upright(100 - deep, 0, 50)), 0, 0);
        assertEquals(List.of("Zed"), t.collidingNow().get("Amy"));

        // Still overlapping, but only a little: the contact is held, the collision is not.
        t.update(Map.of("Amy", upright(0, 0, 50), "Zed", upright(98, 0, 50)), 20, 1);
        assertEquals(List.of("Zed"), t.contactsByPlayer().get("Amy"));
        assertEquals(null, t.collidingNow().get("Amy"));
    }
}
