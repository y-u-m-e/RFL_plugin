package com.rfl;

/**
 * One body part: a segment between two scene-space points (x, y horizontal, z height up-positive;
 * local units, 128 = one tile) swept by a radius.
 */
final class Capsule
{
    private static final double EPSILON = 1e-9;

    /** leftLeg, rightLeg, torso, leftArm, rightArm or head. */
    final String name;
    final double ax;
    final double ay;
    final double az;
    final double bx;
    final double by;
    final double bz;
    final double radius;

    Capsule(String name, double ax, double ay, double az, double bx, double by, double bz, double radius)
    {
        this.name = name;
        this.ax = ax;
        this.ay = ay;
        this.az = az;
        this.bx = bx;
        this.by = by;
        this.bz = bz;
        this.radius = radius;
    }

    /** Sum of radii minus the closest distance between the two segments; 0 or less means apart. */
    static double penetration(Capsule a, Capsule b)
    {
        double[] p = closestPoints(a, b);
        return a.radius + b.radius - Math.sqrt(sq(p[3] - p[0]) + sq(p[4] - p[1]) + sq(p[5] - p[2]));
    }

    /**
     * Closest points between the two segments (Ericson, Real-Time Collision Detection 5.1.9),
     * handling parallel and zero-length segments.
     *
     * @return {x, y, z} on a's segment followed by {x, y, z} on b's
     */
    static double[] closestPoints(Capsule a, Capsule b)
    {
        double d1x = a.bx - a.ax;
        double d1y = a.by - a.ay;
        double d1z = a.bz - a.az;
        double d2x = b.bx - b.ax;
        double d2y = b.by - b.ay;
        double d2z = b.bz - b.az;
        double rx = a.ax - b.ax;
        double ry = a.ay - b.ay;
        double rz = a.az - b.az;
        double lenA = d1x * d1x + d1y * d1y + d1z * d1z;
        double lenB = d2x * d2x + d2y * d2y + d2z * d2z;
        double f = d2x * rx + d2y * ry + d2z * rz;
        double s;
        double t;

        if (lenA <= EPSILON && lenB <= EPSILON)
        {
            s = 0;
            t = 0;
        }
        else if (lenA <= EPSILON)
        {
            s = 0;
            t = clamp(f / lenB);
        }
        else
        {
            double c = d1x * rx + d1y * ry + d1z * rz;
            if (lenB <= EPSILON)
            {
                t = 0;
                s = clamp(-c / lenA);
            }
            else
            {
                double dot = d1x * d2x + d1y * d2y + d1z * d2z;
                double denom = lenA * lenB - dot * dot;
                // Parallel segments: any s works, so start from a's first endpoint.
                s = denom > EPSILON ? clamp((dot * f - c * lenB) / denom) : 0;
                t = (dot * s + f) / lenB;
                if (t < 0)
                {
                    t = 0;
                    s = clamp(-c / lenA);
                }
                else if (t > 1)
                {
                    t = 1;
                    s = clamp((dot - c) / lenA);
                }
            }
        }

        return new double[]{
            a.ax + d1x * s, a.ay + d1y * s, a.az + d1z * s,
            b.ax + d2x * t, b.ay + d2y * t, b.az + d2z * t,
        };
    }

    private static double clamp(double v)
    {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static double sq(double v)
    {
        return v * v;
    }
}
