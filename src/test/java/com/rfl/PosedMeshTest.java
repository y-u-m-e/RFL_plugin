package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PosedMeshTest
{
    private static double[] p(double x, double y, double z)
    {
        return new double[]{x, y, z};
    }

    private static boolean hit(double[][] a, double[][] b)
    {
        boolean ab = PosedMesh.trianglesIntersect(a[0], a[1], a[2], b[0], b[1], b[2]);
        boolean ba = PosedMesh.trianglesIntersect(b[0], b[1], b[2], a[0], a[1], a[2]);
        assertEquals("symmetric", ab, ba);
        return ab;
    }

    /** Flat triangle on the z = 0 plane. */
    private static final double[][] FLAT = {p(0, 0, 0), p(10, 0, 0), p(0, 10, 0)};

    @Test
    public void crossingTrianglesIntersect()
    {
        double[][] wall = {p(40, -20, 40), p(40, 20, 40), p(40, 0, 80)};
        double[][] through = {p(30, 0, 50), p(50, 0, 50), p(40, 0, 70)};
        assertTrue(hit(wall, through));
        // Pierces the flat triangle's interior from below to above.
        assertTrue(hit(FLAT, new double[][]{p(2, 2, -5), p(3, 2, 5), p(2, 3, 5)}));
    }

    @Test
    public void separatedTrianglesDoNotIntersect()
    {
        double[][] wall = {p(40, -20, 40), p(40, 20, 40), p(40, 0, 80)};
        double[][] beside = {p(30, 30, 50), p(50, 30, 50), p(40, 30, 70)};
        assertFalse(hit(wall, beside));
        // Planes cross, triangles don't: entirely above the flat one.
        assertFalse(hit(FLAT, new double[][]{p(2, 2, 1), p(3, 2, 5), p(2, 3, 5)}));
        // Straddles the flat triangle's plane but outside its edges.
        assertFalse(hit(FLAT, new double[][]{p(20, 20, -5), p(21, 20, 5), p(20, 21, 5)}));
    }

    @Test
    public void touchingAtAnEdgeOrVertexCounts()
    {
        assertTrue("shared edge", hit(FLAT, new double[][]{p(0, 0, 0), p(10, 0, 0), p(0, 0, 10)}));
        assertTrue("vertex on interior", hit(FLAT, new double[][]{p(2, 2, 0), p(2, 2, 10), p(5, 5, 10)}));
        assertTrue("shared vertex", hit(FLAT, new double[][]{p(0, 0, 0), p(-5, -5, 10), p(-5, 0, 10)}));
    }

    @Test
    public void coplanarOverlappingIntersect()
    {
        assertTrue("edges cross", hit(FLAT, new double[][]{p(2, 2, 0), p(12, 2, 0), p(2, 12, 0)}));
        assertTrue("one inside the other", hit(FLAT, new double[][]{p(1, 1, 0), p(2, 1, 0), p(1, 2, 0)}));
    }

    @Test
    public void coplanarSeparatedDoNotIntersect()
    {
        assertFalse(hit(FLAT, new double[][]{p(20, 20, 0), p(30, 20, 0), p(20, 30, 0)}));
        // Mirror image across the hypotenuse, a hair away.
        assertFalse(hit(FLAT, new double[][]{p(10.1, 0.1, 0), p(10.1, 10.1, 0), p(0.1, 10.1, 0)}));
    }

    @Test
    public void degenerateTrianglesNeverIntersect()
    {
        // A zero-area sliver (a segment) piercing the flat triangle.
        assertFalse(hit(FLAT, new double[][]{p(1, 1, -5), p(1, 1, 5), p(1, 1, 5)}));
        assertFalse(hit(FLAT, new double[][]{p(1, 1, 0), p(1, 1, 0), p(1, 1, 0)}));
    }

    @Test
    public void transformMatchesBodyRotation()
    {
        // Sole vertex at the origin plus three head vertices at model (10, height 100, 3).
        float[] xs = {0, 10, 10, 10};
        float[] ys = {0, -100, -100, -100};
        float[] zs = {0, 3, 3, 3};
        int orientation = 300;
        Body body = Body.from(xs, ys, zs, 4, orientation, 1000, 2000);
        PosedMesh mesh = PosedMesh.from(xs, ys, zs, 4, new int[]{0}, new int[]{1}, new int[]{2}, 1,
            orientation, 1000, 2000);

        Capsule head = body.parts.get(body.parts.size() - 1);
        assertEquals("head", head.name);
        assertEquals(head.ax, mesh.x[1], 1e-3);
        assertEquals(head.ay, mesh.y[1], 1e-3);
        assertEquals(100, mesh.z[1], 1e-3);
        assertEquals(0, mesh.z[0], 1e-3);
        assertEquals(1000, mesh.x[0], 1e-3);
        assertEquals(2000, mesh.y[0], 1e-3);

        // A quarter turn: x' = z, y' = -x.
        PosedMesh quarter = PosedMesh.from(xs, ys, zs, 4, new int[]{0}, new int[]{1}, new int[]{2}, 1, 512, 0, 0);
        assertEquals(3, quarter.x[1], 1e-3);
        assertEquals(-10, quarter.y[1], 1e-3);
    }

    @Test
    public void fullyTransparentFacesAreSkipped()
    {
        float[] xs = {0, 10, 0, 0};
        float[] ys = {0, 0, -10, 0};
        float[] zs = {0, 0, 0, 10};
        int[] f1 = {0, 0};
        int[] f2 = {1, 1};
        int[] f3 = {2, 3};
        PosedMesh mesh = PosedMesh.from(xs, ys, zs, 4, f1, f2, f3, 2, new byte[]{0, (byte) 255}, null, 0, 0, 0);
        assertEquals(1, mesh.triangles);
    }
}
