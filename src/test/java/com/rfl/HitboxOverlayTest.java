package com.rfl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HitboxOverlayTest
{
    @Test
    public void ringOfALyingCapsuleStandsUpright()
    {
        // Capsule lying along scene x (a foot): its end rings must be perpendicular to x.
        double[][] ring = HitboxOverlay.ringPoints(new double[]{100, 200, 5}, new double[]{1, 0, 0}, 8, 12);
        double minH = Double.MAX_VALUE;
        double maxH = -Double.MAX_VALUE;
        for (double[] p : ring)
        {
            assertEquals("perpendicular to the axis", 100, p[0], 1e-9);
            assertEquals("radius", 8, Math.hypot(p[1] - 200, p[2] - 5), 1e-9);
            minH = Math.min(minH, p[2]);
            maxH = Math.max(maxH, p[2]);
        }
        assertEquals("ring spans the full height of a vertical circle", 16, maxH - minH, 1e-6);
    }

    @Test
    public void ringOfAnUprightCapsuleLiesFlat()
    {
        double[][] ring = HitboxOverlay.ringPoints(new double[]{0, 0, 50}, new double[]{0, 0, 1}, 10, 12);
        for (double[] p : ring)
        {
            assertEquals(50, p[2], 1e-9);
            assertEquals(10, Math.hypot(p[0], p[1]), 1e-9);
        }
    }
}
