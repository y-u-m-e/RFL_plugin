package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/** {@link ContactTracker}: handegg-gated collisions between any two players, held until bounds separate. */
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
    private static final Set<String> ALL = Set.of("Amy", "Zed", "Bo", "A", "B", "C", "Me");
    private static final Set<String> NONE = Collections.emptySet();
    private static final Map<String, PosedMesh> NO_MESHES = Collections.emptyMap();

    /** Local x/y to a recognisable fake world tile and scene tile, on world 330. */
    private static ContactTracker tracker()
    {
        return new ContactTracker((x, y) -> new int[]{1000 + (int) Math.floor(x), 2000 + (int) Math.floor(y), 0,
            3000 + (int) Math.floor(x), 4000 + (int) Math.floor(y)}, () -> 330);
    }

    /** A mesh of the given triangles, shifted dx along x. */
    private static PosedMesh mesh(double dx, double[][]... triangles)
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
                x[v] = (float) (triangles[t][k][0] + dx);
                y[v] = (float) triangles[t][k][1];
                z[v] = (float) triangles[t][k][2];
                faces[v] = v;
            }
        }
        return new PosedMesh(x, y, z, faces);
    }

    private static PosedMesh mesh(double[][]... triangles)
    {
        return mesh(0, triangles);
    }

    @Test
    public void savesBothNamesBallHoldersTimesTileAndWorld()
    {
        ContactTracker t = tracker();
        // A/B touch near x 40; Me/Amy (the local player's own pair) touch 1000 units away.
        Map<String, PosedMesh> touching = Map.of("A", mesh(WALL), "B", mesh(THROUGH),
            "Me", mesh(1000, WALL), "Amy", mesh(1000, THROUGH));

        assertEquals(2, t.update(touching, Set.of("B", "Me", "Amy"), 1000, 5, false).size());
        assertTrue(t.takeFinished().isEmpty());
        assertTrue(t.update(NO_MESHES, NONE, 1600, 6, false).isEmpty());

        List<Collision> done = t.takeFinished();
        assertEquals(2, done.size());
        Collision ab = done.get(0).a.equals("A") ? done.get(0) : done.get(1);
        Collision self = ab == done.get(0) ? done.get(1) : done.get(0);

        assertEquals("A", ab.a);
        assertEquals("B", ab.b);
        assertEquals(List.of("B"), ab.ball);
        assertEquals(1000, ab.startMs);
        assertEquals(1600, ab.endMs);
        assertEquals(5, ab.startTick);
        assertEquals(6, ab.endTick);
        assertEquals(330, ab.world);
        // Centroid of WALL + THROUGH is scene (40, 0).
        assertEquals(1040, ab.x);
        assertEquals(2000, ab.y);
        assertEquals(0, ab.plane);
        assertEquals(3040, ab.sx);
        assertEquals(4000, ab.sy);
        assertEquals(1, ab.maxTriangles);

        assertEquals("self pairs are tracked like any other", "Amy", self.a);
        assertEquals("Me", self.b);
        assertEquals("both holders are named", List.of("Amy", "Me"), self.ball);
        assertEquals(2040, self.x);
    }

    @Test
    public void maxTrianglesIsTheLargestPerTickSample()
    {
        ContactTracker t = tracker();
        PosedMesh zed = mesh(WALL);
        t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH)), ALL, 1000, 1, false);

        // Same tick, no display: stops at the first touching pair and takes no sample.
        t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH, THROUGH_2)), ALL, 1010, 1, false);
        assertEquals(1, t.overlaps().get(0).triangles);

        // First update of a new tick: full count, sampled into the max.
        t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH, THROUGH_2)), ALL, 1020, 2, false);
        assertEquals(2, t.overlaps().get(0).triangles);
        t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH)), ALL, 1030, 3, false);

        t.update(Map.of("Zed", zed, "Amy", mesh(FAR)), ALL, 1040, 4, false);
        Collision c = t.takeFinished().get(0);
        assertEquals(2, c.maxTriangles);
        // The saved tile is the last touching update's.
        assertEquals(1040, c.x);
    }

    @Test
    public void detailCountsFullyButDoesNotChangeTheSavedCount()
    {
        ContactTracker t = tracker();
        PosedMesh zed = mesh(WALL);
        t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH)), ALL, 0, 1, true);

        t.update(Map.of("Zed", zed, "Amy", mesh(THROUGH, THROUGH_2)), ALL, 10, 1, true);
        assertEquals(2, t.overlaps().get(0).triangles);

        t.update(Map.of("Zed", zed, "Amy", mesh(FAR)), ALL, 20, 1, true);
        assertEquals(1, t.takeFinished().get(0).maxTriangles);
    }

    @Test
    public void walkingThroughSomeoneIsOneCollision()
    {
        // Surfaces cross going in, the bodies sit inside each other with nothing crossing, then
        // cross again going out. That must be one collision, not two.
        assertFalse(PosedMesh.trianglesIntersect(WALL[0], WALL[1], WALL[2], NEAR[0], NEAR[1], NEAR[2]));
        ContactTracker t = tracker();
        PosedMesh amy = mesh(WALL);
        assertEquals(1, t.update(Map.of("Amy", amy, "Zed", mesh(THROUGH)), ALL, 0, 0, false).size());

        assertTrue(t.update(Map.of("Amy", amy, "Zed", mesh(NEAR)), ALL, 20, 1, false).isEmpty());
        assertNull(t.collidingNow().get("Amy"));
        // Still listed for the debug panel: bounds overlap, nothing touching.
        ContactTracker.Overlap near = t.overlaps().get(0);
        assertEquals(0, near.triangles);
        assertNull(near.hits);

        assertTrue(t.update(Map.of("Amy", amy, "Zed", mesh(THROUGH)), ALL, 40, 2, false).isEmpty());
        assertTrue(t.takeFinished().isEmpty());
        t.update(Map.of("Amy", amy, "Zed", mesh(FAR)), ALL, 60, 3, false);
        List<Collision> done = t.takeFinished();
        assertEquals(1, done.size());
        assertEquals(0, done.get(0).startMs);
        assertEquals(60, done.get(0).endMs);
    }

    @Test
    public void playerLeavingViewEndsTheCollision()
    {
        ContactTracker t = tracker();
        t.update(Map.of("A", mesh(WALL), "B", mesh(THROUGH)), ALL, 0, 0, false);
        t.update(Map.of("A", mesh(WALL)), ALL, 20, 0, false);
        assertEquals(1, t.takeFinished().size());
    }

    @Test
    public void startsOnlyWithAHandeggHolderInThePairAndDroppingItDoesNotEndIt()
    {
        ContactTracker t = tracker();
        Map<String, PosedMesh> touching = Map.of("A", mesh(WALL), "B", mesh(THROUGH));
        assertTrue(t.update(touching, Set.of("Someone else"), 0, 0, false).isEmpty());
        t.update(NO_MESHES, NONE, 20, 1, false);
        assertTrue(t.takeFinished().isEmpty());

        t.update(touching, NONE, 40, 2, false);
        assertEquals("picking the handegg up mid-overlap starts it", 1,
            t.update(touching, Set.of("A"), 60, 3, false).size());
        t.update(touching, NONE, 80, 4, false);
        assertTrue(t.takeFinished().isEmpty());
        t.update(Map.of("A", mesh(WALL), "B", mesh(FAR)), NONE, 100, 5, false);
        List<Collision> done = t.takeFinished();
        assertEquals(1, done.size());
        assertEquals(60, done.get(0).startMs);
        assertEquals(List.of("A"), done.get(0).ball);
    }

    @Test
    public void pairsWithoutAHolderAreNeverCheckedEvenWithDetailOn()
    {
        // A touches both B and C; B and C don't touch each other. Neither holds a handegg.
        Map<String, PosedMesh> meshes = Map.of("A", mesh(WALL), "B", mesh(THROUGH), "C", mesh(THROUGH_2),
            "Bo", mesh(FAR));

        // detail=true simulates Show hitboxes / Show touching triangles being on: it must not
        // widen which pairs get triangle-checked.
        ContactTracker detail = tracker();
        assertTrue("no handegg, no collision", detail.update(meshes, NONE, 0, 0, true).isEmpty());
        assertNull("not checked, so not even listed", detail.collidingNow().get("A"));
        assertTrue("not checked, so not even in the overlap list", detail.overlaps().isEmpty());

        ContactTracker noDetail = tracker();
        noDetail.update(meshes, NONE, 0, 0, false);
        assertTrue(noDetail.overlaps().isEmpty());
    }

    @Test
    public void displayStillGetsAFullHitCountOnAHandeggPairButNotOnANonHolderPair()
    {
        // A touches both B (holder) and C (no holder); both touch, per WALL/THROUGH/THROUGH_2.
        Map<String, PosedMesh> meshes = Map.of("A", mesh(WALL), "B", mesh(THROUGH), "C", mesh(THROUGH_2));

        // detail=true simulates Show hitboxes / Show touching triangles being on.
        ContactTracker t = tracker();
        List<PosedMesh.Hits> started = t.update(meshes, Set.of("B"), 0, 0, true);

        assertEquals("the handegg pair starts a collision", 1, started.size());
        assertNotNull("handegg pair is triangle-checked and gets hits", t.hits("A", "B"));
        assertNull("non-holder pair is never triangle-checked, display or not", t.hits("A", "C"));
        assertEquals(List.of("B"), t.collidingNow().get("A"));
        assertEquals("only the handegg pair is in the overlap list", 1, t.overlaps().size());
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
        fromBFirst.update(insertBFirst, ALL, 0, 0, false);

        Map<String, PosedMesh> insertAFirst = new LinkedHashMap<>();
        insertAFirst.put("Amy", a);
        insertAFirst.put("Zed", b);
        ContactTracker fromAFirst = tracker();
        fromAFirst.update(insertAFirst, ALL, 0, 0, false);

        assertEquals("Amy", fromBFirst.overlaps().get(0).a);
        assertEquals("Zed", fromBFirst.overlaps().get(0).b);
        assertEquals(fromAFirst.overlaps().get(0).a, fromBFirst.overlaps().get(0).a);
    }

    @Test
    public void emptyMapFinishesEveryOpenCollisionAndResetAllowsAFreshStart()
    {
        ContactTracker t = tracker();
        PosedMesh wall = mesh(WALL);
        PosedMesh through = mesh(THROUGH);
        assertFalse(PosedMesh.trianglesIntersect(THROUGH[0], THROUGH[1], THROUGH[2],
            THROUGH_2[0], THROUGH_2[1], THROUGH_2[2]));

        assertEquals(2, t.update(Map.of("A", wall, "B", through, "C", mesh(THROUGH_2)), ALL, 0, 0, false)
            .size());
        t.update(NO_MESHES, NONE, 20, 1, false);
        assertEquals(2, t.takeFinished().size());

        assertEquals(1, t.update(Map.of("A", wall, "B", through), ALL, 40, 2, false).size());
        t.reset();
        assertEquals("reset() lets the same still-touching pair start fresh", 1,
            t.update(Map.of("A", wall, "B", through), ALL, 60, 3, false).size());
    }

    @Test
    public void flushFinishesOpenCollisionsAndResetDropsThem()
    {
        ContactTracker t = tracker();
        Map<String, PosedMesh> touching = Map.of("A", mesh(WALL), "B", mesh(THROUGH));
        t.update(touching, Set.of("A"), 0, 0, false);
        t.flush(99, 7);
        List<Collision> done = t.takeFinished();
        assertEquals(1, done.size());
        assertEquals(99, done.get(0).endMs);
        assertEquals(7, done.get(0).endTick);

        t.update(touching, Set.of("A"), 100, 8, false);
        t.reset();
        t.flush(200, 9);
        assertTrue(t.takeFinished().isEmpty());
    }

    @Test
    public void listsCollisionsInProgressUntilTheyFinish()
    {
        ContactTracker t = tracker();
        t.update(Map.of("B", mesh(WALL), "A", mesh(THROUGH)), Set.of("A"), 1000, 5, false);
        assertEquals(List.of("A ↔ B"), t.inProgress());
        t.update(NO_MESHES, NONE, 1600, 6, false);
        assertTrue(t.inProgress().isEmpty());
    }

    @Test
    public void exposesCollidingNowAndTheTouchingTrianglesOfEachPair()
    {
        ContactTracker t = tracker();
        t.update(Map.of("Amy", mesh(WALL), "Zed", mesh(THROUGH), "Bo", mesh(FAR)), ALL, 0, 0, false);

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
    public void maxTrianglesIsCappedAtMaxHits()
    {
        // 30 copies of each crossing triangle: 900 touching pairs, collected up to the cap.
        double[][][] walls = new double[30][][];
        double[][][] throughs = new double[30][][];
        for (int i = 0; i < 30; i++)
        {
            walls[i] = WALL;
            throughs[i] = THROUGH;
        }
        ContactTracker t = tracker();
        List<PosedMesh.Hits> started = t.update(Map.of("Amy", mesh(walls), "Zed", mesh(throughs)), ALL,
            0, 0, false);
        assertEquals(PosedMesh.MAX_HITS, started.get(0).count);
        t.flush(10, 1);
        assertEquals(PosedMesh.MAX_HITS, t.takeFinished().get(0).maxTriangles);
    }
}
