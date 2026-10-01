package com.rfl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ContactOverlapOverlayTest
{
    @Test
    public void ringPointsCircleTheBodyCentre()
    {
        int[][] points = ContactOverlapOverlay.ringPoints(new Cylinder(100, 200, 50, 0, 100), 4);
        assertEquals(4, points.length);
        assertArrayEquals(new int[]{150, 200}, points[0]);
        assertArrayEquals(new int[]{100, 250}, points[1]);
        assertArrayEquals(new int[]{50, 200}, points[2]);
        assertArrayEquals(new int[]{100, 150}, points[3]);
    }
}
