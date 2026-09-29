package com.rfl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CylinderTest
{
    private final Cylinder a = new Cylinder(0, 0, 50, 0, 100);

    @Test
    public void separated()
    {
        assertEquals(0, Cylinder.overlapDepth(a, new Cylinder(200, 0, 50, 0, 100)));
    }

    @Test
    public void touching()
    {
        assertEquals(0, Cylinder.overlapDepth(a, new Cylinder(100, 0, 50, 0, 100)));
    }

    @Test
    public void overlapping()
    {
        assertEquals(20, Cylinder.overlapDepth(a, new Cylinder(80, 0, 50, 0, 100)));
    }

    @Test
    public void diagonalNeighboursDoNotTouch()
    {
        // Players on diagonally adjacent tiles, 128 apart on both axes: ~181 apart. The old
        // axis-aligned boxes of rotated models overlapped here; cylinders are rotation-invariant.
        assertEquals(0, Cylinder.overlapDepth(new Cylinder(64, 64, 60, 0, 100), new Cylinder(192, 192, 60, 0, 100)));
    }

    @Test
    public void noVerticalOverlapMeansNoContact()
    {
        assertEquals(0, Cylinder.overlapDepth(a, new Cylinder(10, 0, 50, 200, 300)));
    }

    @Test
    public void verticalOverlapLimitsDepth()
    {
        assertEquals(10, Cylinder.overlapDepth(a, new Cylinder(10, 0, 50, 90, 190)));
    }

    @Test
    public void overlapCenterIsTheMiddleOfTheOverlapAlongTheCentreLine()
    {
        // Overlap spans x 30..50 -> centre 40.
        assertArrayEquals(new int[]{40, 0}, Cylinder.overlapCenter(a, new Cylinder(80, 0, 50, 0, 100)));
    }

    @Test
    public void overlapCenterOfConcentricCylindersIsTheCentre()
    {
        assertArrayEquals(new int[]{0, 0}, Cylinder.overlapCenter(a, new Cylinder(0, 0, 30, 0, 100)));
    }
}
