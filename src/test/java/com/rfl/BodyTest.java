package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class BodyTest
{
    private static final String[] PARTS = {
        "leftThigh", "leftShin", "rightThigh", "rightShin", "torso",
        "leftUpperArm", "leftForearm", "rightUpperArm", "rightForearm", "head",
    };

    /** Model space (x, height above soles, z); converted to negative-up Y when fed to Body. */
    private static final class Figure
    {
        final List<double[]> verts = new ArrayList<>();

        Figure box(double x0, double x1, double h0, double h1, double z0, double z1)
        {
            for (double x : new double[]{x0, x1})
            {
                for (double h : new double[]{h0, h1})
                {
                    for (double z : new double[]{z0, z1})
                    {
                        verts.add(new double[]{x, h, z});
                    }
                }
            }
            return this;
        }

        /** Points along a->b every few units, each ringed by 4 points at distance r around the line. */
        Figure limb(double[] a, double[] b, double r)
        {
            double[] d = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
            double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            double[] u = unit(d);
            // Any vector not parallel to u, crossed twice, gives two perpendiculars.
            double[] ref = Math.abs(u[1]) < 0.9 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
            double[] p = unit(cross(u, ref));
            double[] q = cross(u, p);
            int steps = (int) Math.round(len / 5);
            for (int s = 0; s <= steps; s++)
            {
                double t = (double) s / steps;
                for (double[] off : new double[][]{p, q})
                {
                    for (int sign = -1; sign <= 1; sign += 2)
                    {
                        verts.add(new double[]{
                            a[0] + d[0] * t + sign * r * off[0],
                            a[1] + d[1] * t + sign * r * off[1],
                            a[2] + d[2] * t + sign * r * off[2]});
                    }
                }
            }
            return this;
        }

        Body body(int orientation)
        {
            int n = verts.size();
            float[] xs = new float[n];
            float[] ys = new float[n];
            float[] zs = new float[n];
            for (int i = 0; i < n; i++)
            {
                xs[i] = (float) verts.get(i)[0];
                ys[i] = (float) -verts.get(i)[1];
                zs[i] = (float) verts.get(i)[2];
            }
            return Body.from(xs, ys, zs, n, orientation, 1000, 2000);
        }
    }

    /** Straight legs, torso box, head box, arms outstretched horizontally at shoulder height. */
    private static Figure standing()
    {
        return new Figure()
            .limb(new double[]{-20, 0, 0}, new double[]{-20, 85, 0}, 5)
            .limb(new double[]{20, 0, 0}, new double[]{20, 85, 0}, 5)
            .box(-20, 20, 100, 160, -10, 10)
            .box(-10, 10, 175, 200, -10, 10)
            .limb(new double[]{-35, 150, 0}, new double[]{-105, 150, 0}, 5)
            .limb(new double[]{35, 150, 0}, new double[]{105, 150, 0}, 5);
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

    /** Degrees between the capsule's axis and the given scene direction (sign ignored). */
    private static double angleTo(Capsule c, double[] dir)
    {
        double[] axis = unit(new double[]{c.bx - c.ax, c.by - c.ay, c.bz - c.az});
        double[] d = unit(dir);
        double dot = Math.abs(axis[0] * d[0] + axis[1] * d[1] + axis[2] * d[2]);
        return Math.toDegrees(Math.acos(Math.min(1, dot)));
    }

    private static double[] unit(double[] v)
    {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[]{v[0] / len, v[1] / len, v[2] / len};
    }

    private static double[] cross(double[] a, double[] b)
    {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    @Test
    public void figureSplitsIntoTenNamedParts()
    {
        Body body = standing().body(0);
        assertEquals(10, body.parts.size());
        for (String name : PARTS)
        {
            assertNotNull(name, part(body, name));
        }
    }

    @Test
    public void partsSitAtTheirModelPositionsPlusTheBase()
    {
        Body body = standing().body(0);
        Capsule thigh = part(body, "leftThigh");
        Capsule shin = part(body, "leftShin");
        assertEquals(980, thigh.ax, 1e-3);
        assertEquals(2000, thigh.ay, 1e-3);
        // Thigh is the upper half of the leg, shin the lower.
        assertTrue(Math.min(thigh.az, thigh.bz) > Math.max(shin.az, shin.bz) - 6);
        assertEquals(0, Math.min(shin.az, shin.bz), 1e-3);
        assertEquals(85, Math.max(thigh.az, thigh.bz), 1e-3);
        assertEquals(5, thigh.radius, 1e-3);
        assertEquals(1020, part(body, "rightThigh").ax, 1e-3);

        Capsule torso = part(body, "torso");
        assertEquals(1000, torso.ax, 1e-6);
        assertEquals(100, Math.min(torso.az, torso.bz), 1e-6);
        assertEquals(160, Math.max(torso.az, torso.bz), 1e-6);
        assertEquals(15, torso.radius, 1e-6);

        Capsule head = part(body, "head");
        assertEquals(175, Math.min(head.az, head.bz), 1e-6);
        assertEquals(10, head.radius, 1e-6);

        assertEquals(1000, body.centreX);
        assertEquals(2000, body.centreY);
    }

    @Test
    public void outstretchedArmGivesHorizontalUpperArmAndForearm()
    {
        Body body = standing().body(0);
        for (String side : new String[]{"left", "right"})
        {
            Capsule upper = part(body, side + "UpperArm");
            Capsule fore = part(body, side + "Forearm");
            for (Capsule c : new Capsule[]{upper, fore})
            {
                assertTrue(c.name + " horizontal", angleTo(c, new double[]{1, 0, 0}) < 2);
                assertEquals(c.name, 150, c.az, 0.5);
                assertEquals(c.name, 150, c.bz, 0.5);
                assertEquals(c.name, 5, c.radius, 0.5);
            }
            // Upper arm is the half nearer the torso centre line (x = 1000).
            double upperMid = Math.abs((upper.ax + upper.bx) / 2 - 1000);
            double foreMid = Math.abs((fore.ax + fore.bx) / 2 - 1000);
            assertTrue(side, upperMid < foreMid);
            assertEquals(side, 70, length(upper) + length(fore), 6);
        }
    }

    @Test
    public void legBentAtTheKneeGivesThighAndShinAlongEachBone()
    {
        double[] hip = {-20, 85, 0};
        double[] knee = {-20, 45, 30};
        double[] foot = {-20, 0, 0};
        Body body = new Figure()
            .limb(hip, knee, 4)
            .limb(knee, foot, 4)
            .limb(new double[]{20, 0, 0}, new double[]{20, 85, 0}, 5)
            .box(-20, 20, 100, 160, -10, 10)
            .box(-10, 10, 175, 200, -10, 10)
            .body(0);

        // Orientation 0: scene (x, y, height) = (base + x, base + z, h).
        Capsule thigh = part(body, "leftThigh");
        Capsule shin = part(body, "leftShin");
        assertTrue("thigh",
            angleTo(thigh, new double[]{knee[0] - hip[0], knee[2] - hip[2], knee[1] - hip[1]}) < 4);
        assertTrue("shin",
            angleTo(shin, new double[]{foot[0] - knee[0], foot[2] - knee[2], foot[1] - knee[1]}) < 4);
        assertTrue("thigh radius " + thigh.radius, thigh.radius <= 6);
        assertTrue("shin radius " + shin.radius, shin.radius <= 6);
        assertTrue(Math.max(thigh.az, thigh.bz) > Math.max(shin.az, shin.bz));
    }

    @Test
    public void radiusIsClampedToTheLimits()
    {
        Body thin = new Figure()
            .limb(new double[]{-20, 0, 0}, new double[]{-20, 85, 0}, 0.5)
            .limb(new double[]{20, 0, 0}, new double[]{20, 85, 0}, 0.5)
            .box(-20, 20, 100, 160, -10, 10)
            .box(-10, 10, 175, 200, -10, 10)
            .body(0);
        assertEquals(Body.MIN_LIMB_RADIUS, part(thin, "leftShin").radius, 1e-9);
    }

    @Test
    public void rotationKeepsPartSizes()
    {
        Body upright = standing().body(0);
        Body turned = standing().body(512);
        assertEquals(upright.parts.size(), turned.parts.size());
        for (Capsule c : upright.parts)
        {
            Capsule t = part(turned, c.name);
            assertEquals(c.name, c.radius, t.radius, 1e-3);
            assertEquals(c.name, length(c), length(t), 1e-3);
        }
        // A quarter turn maps model (x, z) to (z, -x): the left leg at x = -20 moves to y = base + 20.
        Capsule shin = part(turned, "leftShin");
        assertEquals(1000, shin.ax, 1e-3);
        assertEquals(2020, shin.ay, 1e-3);
        // The outstretched arm turns with the body and stays horizontal.
        assertTrue(angleTo(part(turned, "leftForearm"), new double[]{0, 1, 0}) < 2);
    }

    @Test
    public void tooFewLimbVerticesSkipsTheSegment()
    {
        Figure f = new Figure()
            .limb(new double[]{-20, 0, 0}, new double[]{-20, 85, 0}, 5)
            .limb(new double[]{20, 0, 0}, new double[]{20, 85, 0}, 5)
            .box(-20, 20, 100, 160, -10, 10)
            .box(-10, 10, 175, 200, -10, 10);
        // Four points out at the left: two per segment, below the three a segment needs.
        f.verts.add(new double[]{-40, 150, 0});
        f.verts.add(new double[]{-50, 150, 0});
        f.verts.add(new double[]{-60, 150, 0});
        f.verts.add(new double[]{-70, 150, 0});
        Body body = f.body(0);
        assertNull(part(body, "leftUpperArm"));
        assertNull(part(body, "leftForearm"));
        assertNotNull(part(body, "leftThigh"));
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
