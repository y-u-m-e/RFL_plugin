package com.rfl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CapsuleTest
{
    private static Capsule c(double ax, double ay, double az, double bx, double by, double bz, double r)
    {
        return new Capsule("part", ax, ay, az, bx, by, bz, r);
    }

    @Test
    public void parallelSeparated()
    {
        assertEquals(-40, Capsule.penetration(c(0, 0, 0, 0, 0, 100, 30), c(100, 0, 0, 100, 0, 100, 30)), 1e-9);
    }

    @Test
    public void crossing()
    {
        assertEquals(20, Capsule.penetration(c(-50, 0, 50, 50, 0, 50, 10), c(0, -50, 50, 0, 50, 50, 10)), 1e-9);
    }

    @Test
    public void touchingIsZero()
    {
        assertEquals(0, Capsule.penetration(c(0, 0, 0, 0, 0, 100, 30), c(60, 0, 0, 60, 0, 100, 30)), 1e-9);
    }

    @Test
    public void endToEnd()
    {
        assertEquals(-10, Capsule.penetration(c(0, 0, 0, 100, 0, 0, 10), c(130, 0, 0, 200, 0, 0, 10)), 1e-9);
    }

    @Test
    public void collinearOverlappingSegmentsTouch()
    {
        assertEquals(20, Capsule.penetration(c(0, 0, 0, 100, 0, 0, 10), c(50, 0, 0, 150, 0, 0, 10)), 1e-9);
    }

    @Test
    public void degeneratePointSegments()
    {
        assertEquals(-35, Capsule.penetration(c(0, 0, 0, 0, 0, 0, 10), c(0, 30, 40, 0, 30, 40, 5)), 1e-9);
        // Point against a segment, either way round.
        assertEquals(-10, Capsule.penetration(c(50, 20, 0, 50, 20, 0, 5), c(0, 0, 0, 100, 0, 0, 5)), 1e-9);
        assertEquals(-10, Capsule.penetration(c(0, 0, 0, 100, 0, 0, 5), c(50, 20, 0, 50, 20, 0, 5)), 1e-9);
    }

    @Test
    public void closestPointsOfCrossingSegments()
    {
        double[] p = Capsule.closestPoints(c(-50, 0, 50, 50, 0, 50, 10), c(10, -50, 0, 10, 50, 0, 10));
        assertEquals(10, p[0], 1e-9);
        assertEquals(0, p[1], 1e-9);
        assertEquals(50, p[2], 1e-9);
        assertEquals(10, p[3], 1e-9);
        assertEquals(0, p[4], 1e-9);
        assertEquals(0, p[5], 1e-9);
    }
}
