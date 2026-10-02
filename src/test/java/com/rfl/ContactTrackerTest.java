package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.google.gson.GsonBuilder;

public class ContactTrackerTest
{
    /** Triangle in the plane x = 40. */
    private static final double[][] WALL = {{40, -20, 40}, {40, 20, 40}, {40, 0, 80}};
    /** Triangle in the plane y = 0, crossing WALL. */
    private static final double[][] THROUGH = {{30, 0, 50}, {50, 0, 50}, {40, 0, 70}};
    /** Triangle in the plane y = 10, also crossing WALL. */
    private static final double[][] THROUGH_2 = {{30, 10, 45}, {50, 10, 45}, {40, 10, 55}};
    /** Inside WALL's bounds but clear of it: WALL spans z 40-44 at y = 18. */
    private static final double[][] NEAR = {{30, 18, 70}, {50, 18, 70}, {40, 18, 80}};
    /** Clear of WALL's bounds. */
    private static final double[][] FAR = {{300, 0, 50}, {320, 0, 50}, {310, 0, 70}};

    /** Everyone holds a handegg, so the gate never gets in the way of the tests that aren't about it. */
    private static final Set<String> ALL = Set.of("Amy", "Zed", "Bo", "A", "B", "C", "Me", "Other Guy");
    private static final Set<String> NONE = Collections.emptySet();

    /** Scene x/y to a recognisable fake world tile. */
    private static ContactTracker tracker()
    {
        return new ContactTracker((x, y) -> new int[]{1000 + (int) Math.floor(x), 2000 + (int) Math.floor(y), 0});
    }

    /** A mesh of the given triangles. */
    private static PosedMesh mesh(double[][]... triangles)
    {
        int n = triangles.length * 3;
        float[] x = new float[n];
        float[] y = new float[n];
        float[] z = new float[n];
        int[] faces = new int[n];
        for (int t = 0; t < triangles.length; t++)
        {
            for (int k = 0; k < 3; k++)
            {
                int v = t * 3 + k;
                x[v] = (float) triangles[t][k][0];
                y[v] = (float) triangles[t][k][1];
                z[v] = (float) triangles[t][k][2];
                faces[v] = v;
            }
        }
        return new PosedMesh(x, y, z, faces);
    }

    @Test
    public void depthIsTheTouchingTriangleCountAndTheEndCarriesTheMaxPerTickSample()
    {
        ContactTracker t = tracker();
        PosedMesh zed = mesh(WALL);

        List<RflEvent> e1 = t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH)), "Amy", ALL, false, 1000, 1, false);
        assertEquals("contact_start", e1.get(0).type);
        assertEquals(1, (int) e1.get(0).depth);
        // Centroid of WALL + THROUGH is scene (40, 0).
        assertEquals(1040, (int) e1.get(0).x);
        assertEquals(2000, (int) e1.get(0).y);
        assertEquals(0, (int) e1.get(0).plane);

        // Same tick, no display: stops at the first touching pair and takes no depth sample.
        assertTrue(t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH, THROUGH_2)), "Amy", ALL, false, 1010, 1, false).isEmpty());
        assertEquals(1, t.overlaps().get(0).triangles);

        // First update of a new tick: full count, sampled into the max.
        assertTrue(t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH, THROUGH_2)), "Amy", ALL, false, 1020, 2, false).isEmpty());
        assertEquals(2, t.overlaps().get(0).triangles);
        assertTrue(t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH)), "Amy", ALL, false, 1030, 3, false).isEmpty());

        List<RflEvent> e3 = t.update(Map.of("Zed", zed, "Amy", mesh(FAR)), "Amy", ALL, false, 1040, 4, false);
        assertEquals("contact_end", e3.get(0).type);
        assertEquals(2, (int) e3.get(0).depth);
        // The end reports the tile of the last touching update.
        assertEquals(1040, (int) e3.get(0).x);
    }

    @Test
    public void detailCountsFullyButDoesNotChangeTheReportedDepth()
    {
        ContactTracker t = tracker();
        PosedMesh zed = mesh(WALL);
        t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH)), "Zed", ALL, false, 0, 1, true);

        t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH, THROUGH_2)), "Zed", ALL, false, 10, 1, true);
        assertEquals(2, t.overlaps().get(0).triangles);

        List<RflEvent> end = t.update(Map.of("Zed", zed, "Amy", mesh(FAR)), "Zed", ALL, false, 20, 1, true);
        assertEquals(1, (int) end.get(0).depth);
    }

    @Test
    public void endsOnTheFirstUpdateNoTrianglesTouchEvenWithBoundsOverlapping()
    {
        assertTrue(!PosedMesh.trianglesIntersect(WALL[0], WALL[1], WALL[2], NEAR[0], NEAR[1], NEAR[2]));
        ContactTracker t = tracker();
        PosedMesh amy = mesh(WALL);
        t.update(Map.of("Amy", amy, "Zed", mesh(THROUGH)), "Amy", ALL, false, 0, 0, false);

        List<RflEvent> end = t.update(Map.of("Amy", amy, "Zed", mesh(NEAR)), "Amy", ALL, false, 20, 1, false);
        assertEquals("contact_end", end.get(0).type);
        assertNull(t.collidingNow().get("Amy"));
        // Still listed for the debug panel: bounds overlap, nothing touching.
        ContactTracker.Overlap near = t.overlaps().get(0);
        assertEquals(0, near.triangles);
        assertNull(near.hits);
    }

    @Test
    public void playerLeavingViewEndsContact()
    {
        ContactTracker t = tracker();
        t.update(Map.of("A", mesh(WALL), "B", mesh(THROUGH)), "A", ALL, false, 0, 0, false);
        assertEquals("contact_end", t.update(Map.of("A", mesh(WALL)), "A", ALL, false, 20, 0, false).get(0).type);
    }

    @Test
    public void startAndEndShareAContactIdAndANewContactGetsANewOne()
    {
        ContactTracker t = tracker();
        RflEvent start = t.update(Map.of("Amy", mesh(WALL), "Zed", mesh(THROUGH)), "Amy", ALL, false, 0, 0, false).get(0);
        RflEvent end = t.update(Map.of("Amy", mesh(WALL), "Zed", mesh(FAR)), "Amy", ALL, false, 20, 1, false).get(0);
        RflEvent again = t.update(Map.of("Amy", mesh(WALL), "Zed", mesh(THROUGH)), "Amy", ALL, false, 40, 2, false).get(0);

        assertEquals("contact_start", start.type);
        assertEquals("contact_end", end.type);
        assertEquals(start.contactId, end.contactId);
        assertEquals("contact_start", again.type);
        assertNotEquals(start.contactId, again.contactId);
    }

    @Test
    public void onlyPairsIncludingTheLocalPlayerEmitContactEvents()
    {
        // A touches both B and C; B and C don't touch each other.
        Map<String, PosedMesh> meshes = Map.of("A", mesh(WALL), "B", mesh(THROUGH), "C", mesh(THROUGH_2),
            "Bo", mesh(FAR));

        List<RflEvent> asB = tracker().update(meshes, "B", NONE, true, 0, 0, false);
        assertTrue("no handegg, no events", asB.isEmpty());

        List<RflEvent> asBWithEgg = tracker().update(meshes, "B", Set.of("B"), false, 0, 0, false);
        assertEquals(1, asBWithEgg.size());
        assertEquals("contact_start", asBWithEgg.get(0).type);

        // Bo touches nobody: A~B and A~C are other-other pairs, never contact events.
        ContactTracker bo = tracker();
        List<RflEvent> asBo = bo.update(meshes, "Bo", NONE, true, 0, 0, false);
        assertTrue(asBo.isEmpty());
        // With Show hitboxes on, the other pairs are still checked for local display.
        assertEquals(List.of("B", "C"), sorted(bo.collidingNow().get("A")));

        // With Show hitboxes off and no handegg, other pairs are not checked at all.
        ContactTracker boNoDisplay = tracker();
        boNoDisplay.update(meshes, "Bo", NONE, false, 0, 0, false);
        assertTrue(boNoDisplay.overlaps().isEmpty());
    }

    @Test
    public void selfContactStartsOnlyWhileEitherBodyHoldsAHandeggAndEndsRegardless()
    {
        ContactTracker t = tracker();
        Map<String, PosedMesh> touching = Map.of("Me", mesh(WALL), "Zed", mesh(THROUGH));

        assertTrue(t.update(touching, "Me", NONE, false, 0, 0, false).isEmpty());

        // Zed picks up the handegg while still touching: the contact starts now, ball = other.
        List<RflEvent> start = t.update(touching, "Me", Set.of("Zed"), false, 10, 1, false);
        assertEquals("contact_start", start.get(0).type);
        assertEquals("other", start.get(0).ball);

        // The handegg is dropped: still touching, the contact stays open.
        assertTrue(t.update(touching, "Me", NONE, false, 20, 2, false).isEmpty());

        List<RflEvent> end = t.update(Map.of("Me", mesh(WALL), "Zed", mesh(FAR)), "Me", NONE, false, 30, 3, false);
        assertEquals("contact_end", end.get(0).type);
        assertEquals(start.get(0).contactId, end.get(0).contactId);
        assertEquals("other", end.get(0).ball);
    }

    @Test
    public void ballIsSelfWhenTheLocalPlayerHoldsTheHandeggIncludingWhenBothDo()
    {
        Map<String, PosedMesh> touching = Map.of("Me", mesh(WALL), "Zed", mesh(THROUGH));
        assertEquals("self", tracker().update(touching, "Me", Set.of("Me"), false, 0, 0, false).get(0).ball);
        assertEquals("self", tracker().update(touching, "Me", Set.of("Me", "Zed"), false, 0, 0, false).get(0).ball);
    }

    @Test
    public void collisionSeenOncePerOtherPairContactWithAHandeggAndCarriesNoNames()
    {
        ContactTracker t = tracker();
        Map<String, PosedMesh> others = Map.of("Amy", mesh(WALL), "Zed", mesh(THROUGH), "Me", mesh(FAR));

        assertTrue("no handegg, no witness", t.update(others, "Me", NONE, false, 0, 0, false).isEmpty());

        List<RflEvent> seen = t.update(others, "Me", Set.of("Amy"), false, 10, 1, false);
        assertEquals(1, seen.size());
        RflEvent e = seen.get(0);
        assertEquals("collision_seen", e.type);
        assertEquals(1040, (int) e.x);
        assertEquals(2000, (int) e.y);
        assertEquals(0, (int) e.plane);
        assertNull(e.contactId);
        assertNull(e.depth);
        assertNull(e.ball);
        String json = new GsonBuilder().create().toJson(e);
        assertFalse(json.contains("Amy"));
        assertFalse(json.contains("Zed"));

        // Still touching: no repeat. Separating and touching again: a new witness.
        assertTrue(t.update(others, "Me", Set.of("Amy"), false, 20, 2, false).isEmpty());
        assertTrue(t.update(Map.of("Amy", mesh(WALL), "Zed", mesh(FAR)), "Me", Set.of("Amy"), false, 30, 3, false).isEmpty());
        assertEquals("collision_seen", t.update(others, "Me", Set.of("Amy"), false, 40, 4, false).get(0).type);
    }

    @Test
    public void serializedReportNeverContainsAnotherPlayersName()
    {
        ContactTracker t = tracker();
        List<RflEvent> events = new ArrayList<>();
        events.addAll(t.update(Map.of("Me", mesh(WALL), "Other Guy", mesh(THROUGH), "Amy", mesh(THROUGH_2)),
            "Me", ALL, true, 0, 0, true));
        events.addAll(t.update(Map.of("Me", mesh(WALL)), "Me", ALL, true, 20, 1, true));
        assertEquals(4, events.size());

        RflReport report = new RflReport("Me", "install-1", 330, 30L, true, "", List.of(), events,
            new RflReport.Features(true, true));
        String json = new GsonBuilder().create().toJson(report);

        assertTrue(json.contains("\"rsn\":\"Me\""));
        assertFalse(json.contains("Other Guy"));
        assertFalse(json.contains("Amy"));
    }

    @Test
    public void pairOrderIndependentOfInsertion()
    {
        PosedMesh a = mesh(WALL);
        PosedMesh b = mesh(THROUGH);

        Map<String, PosedMesh> insertBFirst = new LinkedHashMap<>();
        insertBFirst.put("Zed", b);
        insertBFirst.put("Amy", a);
        ContactTracker fromBFirst = tracker();
        fromBFirst.update(insertBFirst, "Amy", ALL, false, 0, 0, false);

        Map<String, PosedMesh> insertAFirst = new LinkedHashMap<>();
        insertAFirst.put("Amy", a);
        insertAFirst.put("Zed", b);
        ContactTracker fromAFirst = tracker();
        fromAFirst.update(insertAFirst, "Amy", ALL, false, 0, 0, false);

        assertEquals("Amy", fromBFirst.overlaps().get(0).a);
        assertEquals("Zed", fromBFirst.overlaps().get(0).b);
        assertEquals(fromAFirst.overlaps().get(0).a, fromBFirst.overlaps().get(0).a);
    }

    @Test
    public void emptyMapClosesEveryOpenPairAndResetAllowsFreshStart()
    {
        ContactTracker t = tracker();

        // A touches both B and C; B and C don't touch each other, so two pairs are open.
        PosedMesh wall = mesh(WALL);
        PosedMesh through = mesh(THROUGH);
        PosedMesh through2 = mesh(THROUGH_2);
        assertTrue(!PosedMesh.trianglesIntersect(THROUGH[0], THROUGH[1], THROUGH[2],
            THROUGH_2[0], THROUGH_2[1], THROUGH_2[2]));

        List<RflEvent> started = t.update(Map.of("A", wall, "B", through, "C", through2), "A", ALL, false, 0, 0, false);
        assertEquals(2, started.size());
        assertTrue(started.stream().allMatch(e -> "contact_start".equals(e.type)));
        assertNotEquals("two simultaneous touches get two ids", started.get(0).contactId, started.get(1).contactId);

        List<RflEvent> ended = t.update(Collections.emptyMap(), null, NONE, false, 20, 1, false);
        assertEquals("update(emptyMap) should close every open pair, one contact_end each", 2, ended.size());
        assertTrue(ended.stream().allMatch(e -> "contact_end".equals(e.type)));

        List<RflEvent> restartedBeforeReset = t.update(Map.of("A", wall, "B", through), "A", ALL, false, 40, 2, false);
        assertEquals(1, restartedBeforeReset.size());
        assertEquals("contact_start", restartedBeforeReset.get(0).type);

        t.reset();

        List<RflEvent> restartedAfterReset = t.update(Map.of("A", wall, "B", through), "A", ALL, false, 60, 3, false);
        assertEquals("reset() should let the same still-touching pair start fresh", 1, restartedAfterReset.size());
        assertEquals("contact_start", restartedAfterReset.get(0).type);
        assertEquals("ids are never reused after reset", 3, (int) restartedAfterReset.get(0).contactId);
    }

    @Test
    public void exposesCollidingNowAndTheTouchingTrianglesOfEachPair()
    {
        ContactTracker t = tracker();
        t.update(Map.of("Amy", mesh(WALL), "Zed", mesh(THROUGH), "Bo", mesh(FAR)), "Amy", ALL, false, 0, 0, false);

        assertEquals(List.of("Zed"), t.collidingNow().get("Amy"));
        assertEquals(List.of("Amy"), t.collidingNow().get("Zed"));
        assertNull(t.collidingNow().get("Bo"));

        PosedMesh.Hits hits = t.hits("Zed", "Amy");
        assertNotNull(hits);
        assertEquals(1, hits.count);
        assertNull(t.hits("Amy", "Bo"));
        assertEquals(1, t.overlaps().size());
    }

    @Test
    public void depthIsCappedAtMaxHits()
    {
        // 30 copies of each crossing triangle: 900 touching pairs, collected up to the cap.
        double[][][] walls = new double[30][][];
        double[][][] throughs = new double[30][][];
        for (int i = 0; i < 30; i++)
        {
            walls[i] = WALL;
            throughs[i] = THROUGH;
        }
        List<RflEvent> start = tracker().update(Map.of("Amy", mesh(walls), "Zed", mesh(throughs)), "Amy", ALL, false,
            0, 0, false);
        assertEquals(PosedMesh.MAX_HITS, (int) start.get(0).depth);
    }

    private static List<String> sorted(List<String> names)
    {
        List<String> copy = new ArrayList<>(names);
        Collections.sort(copy);
        return copy;
    }
}
