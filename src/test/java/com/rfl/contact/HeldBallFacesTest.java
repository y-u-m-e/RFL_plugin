package com.rfl.contact;

import static com.rfl.Fixtures.WALL;
import static com.rfl.Fixtures.mesh;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.rfl.Handegg;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import sh.yumekui.toolkit.geom.TriangleMesh;

/** {@link HeldBallFaces}: the held ball's faces are found as one block and never count as contact. */
public class HeldBallFacesTest
{
    private static final int BALL_MODEL = Handegg.wornModel(ItemID.EASTER18_HANDEGG_LIGHT);
    /** The ball's own model: a quad of two triangles sharing an edge, as the cache stores it. */
    private static final HeldBallFaces.Shape BALL = new HeldBallFaces.Shape(
        new int[] {0, 2}, new int[] {1, 1}, new int[] {2, 3}, 2);
    private static final int[] EQUIPMENT = {0, 0, 0, 2048 + ItemID.EASTER18_HANDEGG_LIGHT, 0, 0, 0, 0, 0, 0, 0, 0};

    /**
     * A player model as the client merges it: a body triangle, then the ball's block (its vertices
     * appended after the body's, its indices shifted by them), then another body triangle.
     * Scene-space corners {x, y, height}; the ball quad sits at {@code ball}.
     */
    private static final class Composite
    {
        final List<double[]> corners = new ArrayList<>();
        final int[] f1 = new int[4];
        final int[] f2 = new int[4];
        final int[] f3 = new int[4];

        Composite(double[][] bodyBefore, double[][] ball, double[][] bodyAfter)
        {
            addTriangle(0, bodyBefore);
            int base = corners.size();
            for (double[] corner : ball)
            {
                corners.add(corner);
            }
            for (int k = 0; k < BALL.faces; k++)
            {
                f1[1 + k] = BALL.a[k] + base;
                f2[1 + k] = BALL.b[k] + base;
                f3[1 + k] = BALL.c[k] + base;
            }
            addTriangle(3, bodyAfter);
        }

        private void addTriangle(int face, double[][] triangle)
        {
            int base = corners.size();
            for (double[] corner : triangle)
            {
                corners.add(corner);
            }
            f1[face] = base;
            f2[face] = base + 1;
            f3[face] = base + 2;
        }

        /** The mesh {@link ContactDetector} builds: model axes (y down), orientation 0, at 0, 0. */
        TriangleMesh mesh(int[] skip)
        {
            int n = corners.size();
            float[] xs = new float[n];
            float[] ys = new float[n];
            float[] zs = new float[n];
            for (int v = 0; v < n; v++)
            {
                xs[v] = (float) corners.get(v)[0];
                zs[v] = (float) corners.get(v)[1];
                ys[v] = (float) -corners.get(v)[2];
            }
            return TriangleMesh.from(xs, ys, zs, n, f1, f2, f3, 4, null, null, 0, 0, 0, skip[0], skip[1]);
        }
    }

    /** Body triangles well away from the opponent at x 40, both touching the floor (height 0). */
    private static final double[][] BODY_FAR = {{300, 0, 0}, {320, 0, 0}, {310, 0, 90}};
    private static final double[][] BODY_FAR_2 = {{300, 30, 0}, {320, 30, 0}, {310, 30, 90}};
    /** A body triangle crossing the opponent's {@link com.rfl.Fixtures#WALL} at x 40. */
    private static final double[][] BODY_THROUGH = {{30, 0, 0}, {50, 0, 0}, {40, 0, 70}};
    /** The ball quad crossing the opponent's wall at x 40, height 50 to 70. */
    private static final double[][] BALL_THROUGH = {{30, -5, 50}, {50, -5, 50}, {30, 5, 70}, {50, 5, 70}};

    private static HeldBallFaces faces()
    {
        return new HeldBallFaces(id -> id == BALL_MODEL ? BALL : null);
    }

    private static ContactTracker tracker()
    {
        return new ContactTracker((x, y) -> TileRef.NONE, () -> 330);
    }

    @Test
    public void findsTheBallAsOneBlockShiftedByTheBodyBeforeIt()
    {
        Composite model = new Composite(BODY_FAR, BALL_THROUGH, BODY_FAR_2);
        int[] range = faces().range("Amy", 0, EQUIPMENT, BALL_MODEL, model.f1, model.f2, model.f3, 4);
        assertArrayEquals(new int[] {1, 3}, range);
    }

    @Test
    public void leavesOutNothingWithoutAUniqueExactMatch()
    {
        // No block has the ball's shape.
        int[] f1 = {0, 3, 6};
        int[] f2 = {1, 4, 7};
        int[] f3 = {2, 5, 8};
        assertEquals(-1, HeldBallFaces.find(f1, f2, f3, 3, BALL));
        // Two blocks with the ball's shape: ambiguous, so nothing is guessed.
        int[] g1 = {0, 2, 4, 6};
        int[] g2 = {1, 1, 5, 5};
        int[] g3 = {2, 3, 6, 7};
        assertEquals(-1, HeldBallFaces.find(g1, g2, g3, 4, BALL));
        // Not holding a ball, or the model can't be loaded: nothing.
        Composite model = new Composite(BODY_FAR, BALL_THROUGH, BODY_FAR_2);
        assertArrayEquals(HeldBallFaces.NONE, faces().range("Amy", 0, EQUIPMENT, -1, model.f1, model.f2, model.f3, 4));
        assertArrayEquals(HeldBallFaces.NONE, new HeldBallFaces(id -> null)
            .range("Amy", 0, EQUIPMENT, BALL_MODEL, model.f1, model.f2, model.f3, 4));
    }

    @Test
    public void ballFacesOverlappingAnOpponentAreNotACollision()
    {
        Composite carrier = new Composite(BODY_FAR, BALL_THROUGH, BODY_FAR_2);
        TriangleMesh opponent = mesh(WALL);
        // Unmasked, the ball crosses the opponent: what used to count.
        assertNotNull(new TriangleMesh.Intersector().intersect(carrier.mesh(HeldBallFaces.NONE), opponent, 1));

        int[] skip = faces().range("Amy", 0, EQUIPMENT, BALL_MODEL, carrier.f1, carrier.f2, carrier.f3, 4);
        ContactTracker t = tracker();
        List<TriangleMesh.Hits> started = t.update(Map.of("Amy", carrier.mesh(skip), "Zed", opponent),
            Set.of("Amy"), 1000, 5, true);
        assertTrue("the ball is not the body", started.isEmpty());
        assertTrue(t.contactsAt(0, 3).isEmpty());
        t.flush(2000, 6);
        assertTrue(t.takeFinished().isEmpty());
    }

    @Test
    public void bodyOverlapIsStillACollision()
    {
        Composite carrier = new Composite(BODY_THROUGH, BALL_THROUGH, BODY_FAR_2);
        int[] skip = faces().range("Amy", 0, EQUIPMENT, BALL_MODEL, carrier.f1, carrier.f2, carrier.f3, 4);
        assertArrayEquals(new int[] {1, 3}, skip);
        ContactTracker t = tracker();
        List<TriangleMesh.Hits> started = t.update(Map.of("Amy", carrier.mesh(skip), "Zed", mesh(WALL)),
            Set.of("Amy"), 1000, 5, true);
        assertEquals(1, started.size());
        t.flush(2000, 6);
        assertEquals(List.of("Amy"), t.takeFinished().get(0).ball);
    }

    @Test
    public void searchesOncePerAppearanceWhateverThePose()
    {
        HeldBallFaces cache = faces();
        Composite model = new Composite(BODY_FAR, BALL_THROUGH, BODY_FAR_2);
        int[] equipment = EQUIPMENT.clone();
        for (int frame = 0; frame < 100; frame++)
        {
            // Poses move vertices only; the face indices, and so the block, stay put.
            assertArrayEquals(new int[] {1, 3},
                cache.range("Amy", 0, equipment, BALL_MODEL, model.f1, model.f2, model.f3, 4));
        }
        assertEquals(1, cache.searches);
        // The client changes the composition's array in place: a new appearance searches again.
        equipment[0] = 2048 + ItemID.BRONZE_FULL_HELM;
        cache.range("Amy", 0, equipment, BALL_MODEL, model.f1, model.f2, model.f3, 4);
        assertEquals(2, cache.searches);
        cache.range("Amy", 0, equipment, BALL_MODEL, model.f1, model.f2, model.f3, 4);
        assertEquals(2, cache.searches);
        // Another player is their own appearance.
        cache.range("Zed", 0, equipment, BALL_MODEL, model.f1, model.f2, model.f3, 4);
        assertEquals(3, cache.searches);
    }
}
