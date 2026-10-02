package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

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
        ContactTracker t = new ContactTracker();
        PosedMesh zed = mesh(WALL);

        List<RflEvent> e1 = t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH)), 1000, 1, false);
        assertEquals("contact_start", e1.get(0).type);
        assertEquals("Amy", e1.get(0).a);
        assertEquals(1, (int) e1.get(0).depth);

        // Same tick, no display: stops at the first touching pair and takes no depth sample.
        assertTrue(t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH, THROUGH_2)), 1010, 1, false).isEmpty());
        assertEquals(1, t.overlaps().get(0).triangles);

        // First update of a new tick: full count, sampled into the max.
        assertTrue(t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH, THROUGH_2)), 1020, 2, false).isEmpty());
        assertEquals(2, t.overlaps().get(0).triangles);
        assertTrue(t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH)), 1030, 3, false).isEmpty());

        List<RflEvent> e3 = t.update(Map.of("Zed", zed, "Amy", mesh(FAR)), 1040, 4, false);
        assertEquals("contact_end", e3.get(0).type);
        assertEquals(2, (int) e3.get(0).depth);
    }

    @Test
    public void detailCountsFullyButDoesNotChangeTheReportedDepth()
    {
        ContactTracker t = new ContactTracker();
        PosedMesh zed = mesh(WALL);
        t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH)), 0, 1, true);

        t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH, THROUGH_2)), 10, 1, true);
        assertEquals(2, t.overlaps().get(0).triangles);

        List<RflEvent> end = t.update(Map.of("Zed", zed, "Amy", mesh(FAR)), 20, 1, true);
        assertEquals(1, (int) end.get(0).depth);
    }

    @Test
    public void endsOnTheFirstUpdateNoTrianglesTouchEvenWithBoundsOverlapping()
    {
        assertTrue(!PosedMesh.trianglesIntersect(WALL[0], WALL[1], WALL[2], NEAR[0], NEAR[1], NEAR[2]));
        ContactTracker t = new ContactTracker();
        PosedMesh amy = mesh(WALL);
        t.update(Map.of("Amy", amy, "Zed", mesh(THROUGH)), 0, 0, false);

        List<RflEvent> end = t.update(Map.of("Amy", amy, "Zed", mesh(NEAR)), 20, 1, false);
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
        ContactTracker t = new ContactTracker();
        t.update(Map.of("A", mesh(WALL), "B", mesh(THROUGH)), 0, 0, false);
        assertEquals("contact_end", t.update(Map.of("A", mesh(WALL)), 20, 0, false).get(0).type);
    }

    @Test
    public void pairOrderIndependentOfInsertion()
    {
        PosedMesh a = mesh(WALL);
        PosedMesh b = mesh(THROUGH);

        Map<String, PosedMesh> insertBFirst = new LinkedHashMap<>();
        insertBFirst.put("Zed", b);
        insertBFirst.put("Amy", a);
        RflEvent fromBFirst = new ContactTracker().update(insertBFirst, 0, 0, false).get(0);

        Map<String, PosedMesh> insertAFirst = new LinkedHashMap<>();
        insertAFirst.put("Amy", a);
        insertAFirst.put("Zed", b);
        RflEvent fromAFirst = new ContactTracker().update(insertAFirst, 0, 0, false).get(0);

        assertEquals("Amy", fromBFirst.a);
        assertEquals("Zed", fromBFirst.b);
        assertEquals(fromAFirst.a, fromBFirst.a);
        assertEquals(fromAFirst.b, fromBFirst.b);
    }

    @Test
    public void emptyMapClosesEveryOpenPairAndResetAllowsFreshStart()
    {
        ContactTracker t = new ContactTracker();

        // A touches both B and C; B and C don't touch each other, so two pairs are open.
        PosedMesh wall = mesh(WALL);
        PosedMesh through = mesh(THROUGH);
        PosedMesh through2 = mesh(THROUGH_2);
        assertTrue(!PosedMesh.trianglesIntersect(THROUGH[0], THROUGH[1], THROUGH[2],
            THROUGH_2[0], THROUGH_2[1], THROUGH_2[2]));

        List<RflEvent> started = t.update(Map.of("A", wall, "B", through, "C", through2), 0, 0, false);
        assertEquals(2, started.size());
        assertTrue(started.stream().allMatch(e -> "contact_start".equals(e.type)));

        List<RflEvent> ended = t.update(Collections.emptyMap(), 20, 1, false);
        assertEquals("update(emptyMap) should close every open pair, one contact_end each", 2, ended.size());
        assertTrue(ended.stream().allMatch(e -> "contact_end".equals(e.type)));

        List<RflEvent> restartedBeforeReset = t.update(Map.of("A", wall, "B", through), 40, 2, false);
        assertEquals(1, restartedBeforeReset.size());
        assertEquals("contact_start", restartedBeforeReset.get(0).type);

        t.reset();

        List<RflEvent> restartedAfterReset = t.update(Map.of("A", wall, "B", through), 60, 3, false);
        assertEquals("reset() should let the same still-touching pair start fresh", 1, restartedAfterReset.size());
        assertEquals("contact_start", restartedAfterReset.get(0).type);
    }

    @Test
    public void exposesCollidingNowAndTheTouchingTrianglesOfEachPair()
    {
        ContactTracker t = new ContactTracker();
        t.update(Map.of("Amy", mesh(WALL), "Zed", mesh(THROUGH), "Bo", mesh(FAR)), 0, 0, false);

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
        List<RflEvent> start = new ContactTracker().update(Map.of("Amy", mesh(walls), "Zed", mesh(throughs)), 0, 0, false);
        assertEquals(PosedMesh.MAX_HITS, (int) start.get(0).depth);
    }
}
