package com.rfl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class FeetTest
{
    // Model space: Y is vertical, negative-up, so the soles are at the largest Y (0 here).
    // Left foot spans x -20..-10, right foot 10..20, both z -5..15; a torso vertex sits high up.
    private static final float[] XS = {-20, -10, -20, -10, 10, 20, 10, 20, 0};
    private static final float[] YS = {0, 0, -5, -5, 0, 0, -5, -5, -150};
    private static final float[] ZS = {-5, 15, 15, -5, -5, 15, 15, -5, 0};

    @Test
    public void findsTwoFeetAtTheSoles()
    {
        List<int[][]> feet = Feet.footprints(XS, YS, ZS, XS.length, 0, 1000, 2000);
        assertEquals(2, feet.size());
    }

    @Test
    public void unrotatedFootprintsSitAtTheirModelPositionPlusTheBase()
    {
        List<int[][]> feet = Feet.footprints(XS, YS, ZS, XS.length, 0, 1000, 2000);
        int[][] left = feet.get(0);
        assertEquals(4, left.length);
        assertArrayEquals(new int[]{980, 1995}, left[0]); // minX, minZ
        assertArrayEquals(new int[]{990, 2015}, left[2]); // maxX, maxZ
        int[][] right = feet.get(1);
        assertArrayEquals(new int[]{1010, 1995}, right[0]);
        assertArrayEquals(new int[]{1020, 2015}, right[2]);
    }

    @Test
    public void rotationKeepsTheFootSize()
    {
        int[][] foot = Feet.footprints(XS, YS, ZS, XS.length, 512, 0, 0).get(0);
        double side = Math.hypot(foot[1][0] - foot[0][0], foot[1][1] - foot[0][1]);
        double end = Math.hypot(foot[2][0] - foot[1][0], foot[2][1] - foot[1][1]);
        assertEquals(10, Math.min(side, end), 1.0);
        assertEquals(20, Math.max(side, end), 1.0);
    }

    @Test
    public void noVerticesMeansNoFeet()
    {
        assertEquals(0, Feet.footprints(new float[0], new float[0], new float[0], 0, 0, 0, 0).size());
    }
}
