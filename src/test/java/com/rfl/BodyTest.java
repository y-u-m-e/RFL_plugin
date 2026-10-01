package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class BodyTest
{
    // Model space: X/Z horizontal, Y vertical and negative-up; soles at y = 0, top of head at y = -200.
    private static final List<float[]> VERTS = new ArrayList<>();

    static
    {
        box(-25, -15, 0, 80, -5, 5);   // left leg
        box(15, 25, 0, 80, -5, 5);     // right leg
        box(-20, 20, 100, 160, -10, 10); // torso
        box(-10, 10, 175, 200, -10, 10); // head
        // Outstretched arms at shoulder height, 60 long.
        for (int x = 40; x <= 100; x += 30)
        {
            VERTS.add(new float[]{-x, -150, 0});
            VERTS.add(new float[]{x, -150, 0});
        }
    }

    private static void box(float x0, float x1, float h0, float h1, float z0, float z1)
    {
        for (float x : new float[]{x0, x1})
        {
            for (float h : new float[]{h0, h1})
            {
                for (float z : new float[]{z0, z1})
                {
                    VERTS.add(new float[]{x, -h, z});
                }
            }
        }
    }

    private static Body figure(int orientation)
    {
        int n = VERTS.size();
        float[] xs = new float[n];
        float[] ys = new float[n];
        float[] zs = new float[n];
        for (int i = 0; i < n; i++)
        {
            xs[i] = VERTS.get(i)[0];
            ys[i] = VERTS.get(i)[1];
            zs[i] = VERTS.get(i)[2];
        }
        return Body.from(xs, ys, zs, n, orientation, 1000, 2000);
    }

    private static Capsule part(Body body, String name)
    {
        for (Capsule c : body.parts)
        {
            if (c.name.equals(name))
            {
                return c;
            }
        }
        return null;
    }

    private static double length(Capsule c)
    {
        return Math.sqrt(Math.pow(c.bx - c.ax, 2) + Math.pow(c.by - c.ay, 2) + Math.pow(c.bz - c.az, 2));
    }

    @Test
    public void figureSplitsIntoSixNamedParts()
    {
        Body body = figure(0);
        assertEquals(6, body.parts.size());
        for (String name : new String[]{"leftLeg", "rightLeg", "torso", "leftArm", "rightArm", "head"})
        {
            assertNotNull(name, part(body, name));
        }
    }

    @Test
    public void partsSitAtTheirModelPositionsPlusTheBase()
    {
        Body body = figure(0);
        Capsule left = part(body, "leftLeg");
        assertEquals(980, left.ax, 1e-6);
        assertEquals(2000, left.ay, 1e-6);
        assertEquals(0, Math.min(left.az, left.bz), 1e-6);
        assertEquals(80, Math.max(left.az, left.bz), 1e-6);
        assertEquals(5, left.radius, 1e-6);
        assertEquals(1020, part(body, "rightLeg").ax, 1e-6);

        Capsule torso = part(body, "torso");
        assertEquals(1000, torso.ax, 1e-6);
        assertEquals(100, Math.min(torso.az, torso.bz), 1e-6);
        assertEquals(160, Math.max(torso.az, torso.bz), 1e-6);
        assertEquals(15, torso.radius, 1e-6);

        Capsule head = part(body, "head");
        assertEquals(175, Math.min(head.az, head.bz), 1e-6);
        assertEquals(10, head.radius, 1e-6);

        Capsule leftArm = part(body, "leftArm");
        assertTrue(Math.max(leftArm.ax, leftArm.bx) <= 960 + 1e-6);
        assertEquals(150, leftArm.az, 1e-6);
        assertEquals(Body.ARM_RADIUS, leftArm.radius, 1e-6);

        assertEquals(1000, body.centreX);
        assertEquals(2000, body.centreY);
    }

    @Test
    public void outstretchedArmCapsuleSpansTheArm()
    {
        Body body = figure(0);
        assertEquals(60, length(part(body, "leftArm")), 1e-6);
        assertEquals(60, length(part(body, "rightArm")), 1e-6);
    }

    @Test
    public void rotationKeepsPartSizes()
    {
        Body upright = figure(0);
        Body turned = figure(512);
        for (Capsule c : upright.parts)
        {
            Capsule t = part(turned, c.name);
            assertEquals(c.name, c.radius, t.radius, 1e-6);
            assertEquals(c.name, length(c), length(t), 1e-6);
        }
        // A quarter turn maps model (x, z) to (z, -x): the left leg at x = -20 moves to y = base + 20.
        Capsule leg = part(turned, "leftLeg");
        assertEquals(1000, leg.ax, 1e-6);
        assertEquals(2020, leg.ay, 1e-6);
    }

    @Test
    public void noVerticesMeansNoParts()
    {
        Body body = Body.from(new float[0], new float[0], new float[0], 0, 0, 5, 6);
        assertTrue(body.parts.isEmpty());
        assertEquals(5, body.centreX);
        assertEquals(6, body.centreY);
    }
}
