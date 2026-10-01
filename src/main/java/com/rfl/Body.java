package com.rfl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntToDoubleFunction;

/**
 * A player's posed model split into capsules: thigh, shin and foot per leg, torso, upper arm and forearm
 * per arm, head. Limb segments follow the bone direction (principal axis of their vertices), so a
 * striding leg or swinging arm stays thin instead of becoming one fat vertical capsule.
 */
final class Body
{
    /** Vertices below this fraction of the model height are legs. */
    static final double LEG_TOP = 0.45;
    /**
     * A leg's vertices within this many local units of its furthest reach from the hip are its
     * foot: fitted on its own so it lies flat heel to toe, and the shin stops at the ankle.
     * Measured from the hip, so a foot raised or kicked back mid-stride is still the foot.
     */
    static final double FOOT_REACH = 20;
    /** Vertices above this fraction of the model height are the head. */
    static final double HEAD_BOTTOM = 0.85;
    /**
     * Between legs and head, vertices within this fraction of the model height of the band's mean
     * x are torso; the rest are arms. Initial value; tune against the hitbox overlay in game.
     */
    static final double TORSO_HALF_WIDTH = 0.15;
    /**
     * Fixed thickness of each part as a fraction of model height, measured from a standing
     * bare-body model 202 units tall (radii 12.8, 7.5, 5.5, 21.7, 6.9, 5.0, 11.5). Fixed rather
     * than refitted per frame, so a part moves with the model instead of swelling mid-stride.
     */
    private static final double MODEL_HEIGHT_REF = 202;
    private static final double THIGH_RADIUS = 12.8 / MODEL_HEIGHT_REF;
    private static final double SHIN_RADIUS = 7.5 / MODEL_HEIGHT_REF;
    /** Half the height of the real foot vertices (about 12 units on a 202-tall model). */
    private static final double FOOT_RADIUS = 5.5 / MODEL_HEIGHT_REF;
    private static final double TORSO_RADIUS = 21.7 / MODEL_HEIGHT_REF;
    private static final double UPPER_ARM_RADIUS = 6.9 / MODEL_HEIGHT_REF;
    private static final double FOREARM_RADIUS = 5.0 / MODEL_HEIGHT_REF;
    private static final double HEAD_RADIUS = 11.5 / MODEL_HEIGHT_REF;
    /** A limb segment with fewer vertices than this is skipped. */
    static final int MIN_SEGMENT_VERTICES = 3;
    /** Power-iteration steps for a principal axis; ample for 3x3. */
    private static final int AXIS_ITERATIONS = 32;

    /** The deepest part pair between two bodies. */
    static final class Contact
    {
        final Capsule partA;
        final Capsule partB;
        /** Penetration of the deepest pair; 0 or less means apart. */
        final double depth;
        /** Ground point midway between the pair's closest points. */
        final int x;
        final int y;

        Contact(Capsule partA, Capsule partB, double depth, int x, int y)
        {
            this.partA = partA;
            this.partB = partB;
            this.depth = depth;
            this.x = x;
            this.y = y;
        }
    }

    final List<Capsule> parts;
    /** Torso centre on the ground, else the player's base point. */
    final int centreX;
    final int centreY;

    Body(List<Capsule> parts, int centreX, int centreY)
    {
        this.parts = parts;
        this.centreX = centreX;
        this.centreY = centreY;
    }

    /** @return the deepest part pair (each part of a against each part of b), or null when either has none */
    static Contact contact(Body a, Body b)
    {
        Capsule bestA = null;
        Capsule bestB = null;
        double best = Double.NEGATIVE_INFINITY;
        for (Capsule pa : a.parts)
        {
            for (Capsule pb : b.parts)
            {
                double depth = Capsule.penetration(pa, pb);
                if (depth > best)
                {
                    best = depth;
                    bestA = pa;
                    bestB = pb;
                }
            }
        }
        if (bestA == null)
        {
            return null;
        }
        double[] p = Capsule.closestPoints(bestA, bestB);
        return new Contact(bestA, bestB, best,
            (int) Math.round((p[0] + p[3]) / 2), (int) Math.round((p[1] + p[4]) / 2));
    }

    /**
     * Same inputs and rotation as {@link Feet#footprints}.
     *
     * @param xs model-space vertex X (horizontal)
     * @param ys model-space vertex Y (vertical, negative-up)
     * @param zs model-space vertex Z (horizontal)
     * @param count number of vertices to read
     * @param orientation actor orientation, 0-2047 for a full turn
     * @param baseX scene X of the player (LocalPoint)
     * @param baseY scene Y of the player (LocalPoint)
     */
    /** Fixed radius of the named part for a model of the given height (see THIGH_RADIUS etc.). */
    static double radiusFor(String name, double height)
    {
        double fraction = name.endsWith("Thigh") ? THIGH_RADIUS
            : name.endsWith("Shin") ? SHIN_RADIUS
            : name.endsWith("Foot") ? FOOT_RADIUS
            : name.endsWith("UpperArm") ? UPPER_ARM_RADIUS
            : name.endsWith("Forearm") ? FOREARM_RADIUS
            : name.equals("head") ? HEAD_RADIUS
            : TORSO_RADIUS;
        return fraction * height;
    }

    static Body from(float[] xs, float[] ys, float[] zs, int count, int orientation, int baseX, int baseY)
    {
        if (count == 0)
        {
            return new Body(Collections.emptyList(), baseX, baseY);
        }

        float bottom = -Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        for (int i = 0; i < count; i++)
        {
            bottom = Math.max(bottom, ys[i]);
            top = Math.min(top, ys[i]);
        }
        // h = height above the soles.
        float[] h = new float[count];
        for (int i = 0; i < count; i++)
        {
            h[i] = bottom - ys[i];
        }
        double height = bottom - top;
        double legTop = LEG_TOP * height;
        double headBottom = HEAD_BOTTOM * height;
        double torsoHalfWidth = TORSO_HALF_WIDTH * height;

        double legSum = 0;
        int legCount = 0;
        double bandSum = 0;
        int bandCount = 0;
        for (int i = 0; i < count; i++)
        {
            if (h[i] < legTop)
            {
                legSum += xs[i];
                legCount++;
            }
            else if (h[i] <= headBottom)
            {
                bandSum += xs[i];
                bandCount++;
            }
        }
        double legMean = legCount == 0 ? 0 : legSum / legCount;
        double bandMean = bandCount == 0 ? 0 : bandSum / bandCount;

        List<Integer> leftLeg = new ArrayList<>();
        List<Integer> rightLeg = new ArrayList<>();
        List<Integer> torso = new ArrayList<>();
        List<Integer> leftArm = new ArrayList<>();
        List<Integer> rightArm = new ArrayList<>();
        List<Integer> head = new ArrayList<>();
        for (int i = 0; i < count; i++)
        {
            if (h[i] < legTop)
            {
                (xs[i] < legMean ? leftLeg : rightLeg).add(i);
            }
            else if (h[i] > headBottom)
            {
                head.add(i);
            }
            else
            {
                double off = xs[i] - bandMean;
                (off < -torsoHalfWidth ? leftArm : off > torsoHalfWidth ? rightArm : torso).add(i);
            }
        }

        // A planted foot's inner edge can cross the centre line: settle near-midline vertices by
        // their nearest clearly-sided neighbour at a similar height.
        settleMidline(leftLeg, rightLeg, xs, h, zs, legMean);

        double angle = orientation * 2 * Math.PI / 2048;
        Frame frame = new Frame(xs, h, zs, height, Math.sin(angle), Math.cos(angle), baseX, baseY);
        double torsoX = 0;
        double torsoZ = 0;
        for (int i : torso)
        {
            torsoX += xs[i];
            torsoZ += zs[i];
        }
        double tx = torso.isEmpty() ? bandMean : torsoX / torso.size();
        double tz = torso.isEmpty() ? 0 : torsoZ / torso.size();
        // Shoulders: top of the torso band, at the torso's side edges.
        double[] leftShoulder = {tx - torsoHalfWidth, headBottom, tz};
        double[] rightShoulder = {tx + torsoHalfWidth, headBottom, tz};

        List<Capsule> parts = new ArrayList<>();
        frame.leg("leftThigh", "leftShin", "leftFoot", leftLeg, parts);
        frame.leg("rightThigh", "rightShin", "rightFoot", rightLeg, parts);
        Capsule torsoPart = frame.upright("torso", torso, parts);
        frame.arm("leftUpperArm", "leftForearm", leftArm, leftShoulder, parts);
        frame.arm("rightUpperArm", "rightForearm", rightArm, rightShoulder, parts);
        frame.upright("head", head, parts);

        if (torsoPart == null)
        {
            return new Body(parts, baseX, baseY);
        }
        return new Body(parts, (int) Math.round(torsoPart.ax), (int) Math.round(torsoPart.ay));
    }

    /** Leg vertices this close to the centre line (local units) are settled by their neighbours. */
    private static final double LEG_MIDLINE_BAND = 8;
    /** Only neighbours within this height of an ambiguous vertex are considered (local units). */
    private static final double LEG_NEIGHBOUR_HEIGHT = 15;

    /**
     * Reassigns leg vertices within LEG_MIDLINE_BAND of the centre line to the side of their
     * nearest clearly-sided leg vertex within LEG_NEIGHBOUR_HEIGHT of their height. With no such
     * neighbour on either side, the centre-line split stands.
     */
    private static void settleMidline(List<Integer> left, List<Integer> right, float[] xs, float[] h, float[] zs,
        double centre)
    {
        List<Integer> clearLeft = new ArrayList<>();
        List<Integer> clearRight = new ArrayList<>();
        List<Integer> ambiguous = new ArrayList<>();
        for (int i : left)
        {
            (Math.abs(xs[i] - centre) < LEG_MIDLINE_BAND ? ambiguous : clearLeft).add(i);
        }
        for (int i : right)
        {
            (Math.abs(xs[i] - centre) < LEG_MIDLINE_BAND ? ambiguous : clearRight).add(i);
        }
        if (ambiguous.isEmpty())
        {
            return;
        }
        List<Integer> newLeft = new ArrayList<>(clearLeft);
        List<Integer> newRight = new ArrayList<>(clearRight);
        for (int i : ambiguous)
        {
            double dl = nearest(i, clearLeft, xs, h, zs);
            double dr = nearest(i, clearRight, xs, h, zs);
            boolean toLeft = dl == Double.MAX_VALUE && dr == Double.MAX_VALUE ? xs[i] < centre : dl <= dr;
            (toLeft ? newLeft : newRight).add(i);
        }
        left.clear();
        left.addAll(newLeft);
        right.clear();
        right.addAll(newRight);
    }

    private static double nearest(int i, List<Integer> candidates, float[] xs, float[] h, float[] zs)
    {
        double best = Double.MAX_VALUE;
        for (int j : candidates)
        {
            if (Math.abs(h[j] - h[i]) > LEG_NEIGHBOUR_HEIGHT)
            {
                continue;
            }
            double dx = xs[j] - xs[i];
            double dh = h[j] - h[i];
            double dz = zs[j] - zs[i];
            best = Math.min(best, Math.sqrt(dx * dx + dh * dh + dz * dz));
        }
        return best;
    }

    /** Model-space vertices (with height above the soles) plus the transform into scene space. */
    private static final class Frame
    {
        final float[] xs;
        final float[] h;
        final float[] zs;
        final double height;
        final double sin;
        final double cos;
        final int baseX;
        final int baseY;

        Frame(float[] xs, float[] h, float[] zs, double height, double sin, double cos, int baseX, int baseY)
        {
            this.xs = xs;
            this.h = h;
            this.zs = zs;
            this.height = height;
            this.sin = sin;
            this.cos = cos;
            this.baseX = baseX;
            this.baseY = baseY;
        }

        /** Vertical capsule through the vertices' (x, z) centroid; radius = mean half-extent in x and z. */
        Capsule upright(String name, List<Integer> idx, List<Capsule> out)
        {
            if (idx.isEmpty())
            {
                return null;
            }
            double sumX = 0;
            double sumZ = 0;
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE;
            float maxZ = -Float.MAX_VALUE;
            float minH = Float.MAX_VALUE;
            float maxH = -Float.MAX_VALUE;
            for (int i : idx)
            {
                sumX += xs[i];
                sumZ += zs[i];
                minX = Math.min(minX, xs[i]);
                maxX = Math.max(maxX, xs[i]);
                minZ = Math.min(minZ, zs[i]);
                maxZ = Math.max(maxZ, zs[i]);
                minH = Math.min(minH, h[i]);
                maxH = Math.max(maxH, h[i]);
            }
            double x = sumX / idx.size();
            double z = sumZ / idx.size();
            double radius = radiusFor(name, height);
            double sx = sceneX(x, z);
            double sy = sceneY(x, z);
            Capsule c = new Capsule(name, sx, sy, minH, sx, sy, maxH, radius);
            out.add(c);
            return c;
        }

        /**
         * A leg as thigh, shin and foot, ordered outward from the hip so it works whichever way the
         * leg points (standing, mid-stride, kicked back). The hip is the mean of the leg's highest
         * quarter. The foot is the vertices within FOOT_REACH of the leg's furthest reach from the
         * hip. The rest runs hip -> knee -> ankle through slice means: nearest quarter, middle
         * third, farthest quarter, each end pushed out to the nearest / farthest vertex. OSRS legs
         * are a handful of vertices, so means of slices keep the parts along the leg.
         */
        void leg(String thigh, String shin, String foot, List<Integer> idx, List<Capsule> out)
        {
            if (idx.size() < 4)
            {
                segment(thigh, idx, out);
                return;
            }
            List<Integer> byHeight = new ArrayList<>(idx);
            byHeight.sort(Comparator.comparingDouble(i -> h[i]));
            int n = byHeight.size();
            double[] hip = centre(byHeight.subList(n - Math.max(1, n / 4), n));
            hip[1] = h[byHeight.get(n - 1)];

            List<Integer> byReach = new ArrayList<>(idx);
            byReach.sort(Comparator.comparingDouble(i -> distance(i, hip)));
            double furthest = distance(byReach.get(byReach.size() - 1), hip);
            List<Integer> leg = new ArrayList<>();
            List<Integer> footIdx = new ArrayList<>();
            for (int i : byReach)
            {
                (distance(i, hip) >= furthest - FOOT_REACH ? footIdx : leg).add(i);
            }

            if (leg.size() >= 4)
            {
                int m = leg.size();
                int q = Math.max(1, m / 4);
                double[] knee = centre(leg.subList(m / 3, Math.max(m / 3 + 1, 2 * m / 3)));
                double[] top = extend(knee, centre(leg.subList(0, q)), leg.subList(0, q));
                double[] ankle = extend(knee, centre(leg.subList(m - q, m)), leg.subList(m - q, m));
                between(thigh, top, knee, out);
                between(shin, knee, ankle, out);
            }
            else
            {
                segment(thigh, leg, out);
            }
            segment(foot, footIdx, out);
        }

        /** Mean (x, height, z) of the vertices. */
        private double[] centre(List<Integer> idx)
        {
            double x = 0;
            double hh = 0;
            double z = 0;
            for (int i : idx)
            {
                x += xs[i];
                hh += h[i];
                z += zs[i];
            }
            return new double[]{x / idx.size(), hh / idx.size(), z / idx.size()};
        }

        /** Capsule from {@code a} to {@code b} (model x, height, z) with the part's fixed radius. */
        private void between(String name, double[] a, double[] b, List<Capsule> out)
        {
            out.add(new Capsule(name, sceneX(a[0], a[2]), sceneY(a[0], a[2]), a[1],
                sceneX(b[0], b[2]), sceneY(b[0], b[2]), b[1], radiusFor(name, height)));
        }

        /**
         * An arm as upper arm and forearm from shoulder to elbow to wrist. Vertices are ordered by
         * distance from the shoulder point (not by height), so a raised or outstretched arm fits
         * as well as a hanging one; each joint is the mean of a slice: nearest quarter (shoulder),
         * middle third (elbow), farthest quarter (wrist), extended to the nearest and farthest
         * vertex along each segment.
         */
        void arm(String upper, String fore, List<Integer> idx, double[] shoulder, List<Capsule> out)
        {
            if (idx.size() < 4)
            {
                segment(upper, idx, out);
                return;
            }
            List<Integer> sorted = new ArrayList<>(idx);
            sorted.sort(Comparator.comparingDouble(i -> distance(i, shoulder)));
            int n = sorted.size();
            int q = Math.max(1, n / 4);
            double[] elbow = centre(sorted.subList(n / 3, Math.max(n / 3 + 1, 2 * n / 3)));
            double[] near = extend(elbow, centre(sorted.subList(0, q)), sorted.subList(0, q));
            double[] far = extend(elbow, centre(sorted.subList(n - q, n)), sorted.subList(n - q, n));
            between(upper, near, elbow, out);
            between(fore, elbow, far, out);
        }

        private double distance(int i, double[] p)
        {
            double dx = xs[i] - p[0];
            double dh = h[i] - p[1];
            double dz = zs[i] - p[2];
            return Math.sqrt(dx * dx + dh * dh + dz * dz);
        }

        /**
         * The point on the ray from {@code from} through {@code to}, pushed out to the furthest
         * projection of the given vertices along that ray.
         */
        private double[] extend(double[] from, double[] to, List<Integer> idx)
        {
            double dx = to[0] - from[0];
            double dh = to[1] - from[1];
            double dz = to[2] - from[2];
            double len = Math.sqrt(dx * dx + dh * dh + dz * dz);
            if (len < 1e-6)
            {
                return to;
            }
            dx /= len;
            dh /= len;
            dz /= len;
            double t = len;
            for (int i : idx)
            {
                t = Math.max(t, (xs[i] - from[0]) * dx + (h[i] - from[1]) * dh + (zs[i] - from[2]) * dz);
            }
            return new double[]{from[0] + t * dx, from[1] + t * dh, from[2] + t * dz};
        }

        /**
         * Capsule along the segment's own principal axis: endpoints at the min/max projection,
         * with the part's fixed radius.
         */
        void segment(String name, List<Integer> idx, List<Capsule> out)
        {
            if (idx.size() < MIN_SEGMENT_VERTICES)
            {
                return;
            }
            double[] axis = axis(idx);
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            double[] dist = new double[idx.size()];
            for (int k = 0; k < idx.size(); k++)
            {
                int i = idx.get(k);
                double t = project(i, axis);
                min = Math.min(min, t);
                max = Math.max(max, t);
                double px = xs[i] - axis[0] - t * axis[3];
                double ph = h[i] - axis[1] - t * axis[4];
                double pz = zs[i] - axis[2] - t * axis[5];
                dist[k] = Math.sqrt(px * px + ph * ph + pz * pz);
            }
            double radius = radiusFor(name, height);
            // The rounded caps add a radius past each end; pull the ends in so the capsule ends
            // where the vertices do (collapsing to the middle if the part is shorter than that).
            double inset = Math.min(radius, (max - min) / 2);
            min += inset;
            max -= inset;
            double ax = axis[0] + min * axis[3];
            double ah = axis[1] + min * axis[4];
            double az = axis[2] + min * axis[5];
            double bx = axis[0] + max * axis[3];
            double bh = axis[1] + max * axis[4];
            double bz = axis[2] + max * axis[5];
            out.add(new Capsule(name, sceneX(ax, az), sceneY(ax, az), ah, sceneX(bx, bz), sceneY(bx, bz), bh, radius));
        }

        /**
         * Principal axis (PCA) of the vertices: their mean, then the largest eigenvector of their
         * 3x3 covariance by power iteration, as {mx, mh, mz, dx, dh, dz} with a unit direction.
         * Falls back to vertical when the points don't spread.
         */
        private double[] axis(List<Integer> idx)
        {
            int n = idx.size();
            double mx = 0;
            double mh = 0;
            double mz = 0;
            for (int i : idx)
            {
                mx += xs[i];
                mh += h[i];
                mz += zs[i];
            }
            mx /= n;
            mh /= n;
            mz /= n;
            double[][] c = new double[3][3];
            for (int i : idx)
            {
                double[] d = {xs[i] - mx, h[i] - mh, zs[i] - mz};
                for (int r = 0; r < 3; r++)
                {
                    for (int k = 0; k < 3; k++)
                    {
                        c[r][k] += d[r] * d[k];
                    }
                }
            }
            // Start from the covariance column with the largest variance rather than a fixed
            // vector, which could be orthogonal to the dominant direction.
            int col = c[0][0] >= c[1][1] && c[0][0] >= c[2][2] ? 0 : c[1][1] >= c[2][2] ? 1 : 2;
            double[] v = {c[0][col], c[1][col], c[2][col]};
            for (int it = 0; it < AXIS_ITERATIONS; it++)
            {
                double len = norm(v);
                if (len < 1e-12)
                {
                    return new double[]{mx, mh, mz, 0, 1, 0};
                }
                double ux = v[0] / len;
                double uh = v[1] / len;
                double uz = v[2] / len;
                v = new double[]{
                    c[0][0] * ux + c[0][1] * uh + c[0][2] * uz,
                    c[1][0] * ux + c[1][1] * uh + c[1][2] * uz,
                    c[2][0] * ux + c[2][1] * uh + c[2][2] * uz};
            }
            double len = norm(v);
            if (len < 1e-12)
            {
                return new double[]{mx, mh, mz, 0, 1, 0};
            }
            return new double[]{mx, mh, mz, v[0] / len, v[1] / len, v[2] / len};
        }

        /** Signed distance of vertex i along the axis from the axis' mean point. */
        private double project(int i, double[] axis)
        {
            return (xs[i] - axis[0]) * axis[3] + (h[i] - axis[1]) * axis[4] + (zs[i] - axis[2]) * axis[5];
        }

        private static double norm(double[] v)
        {
            return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        }

        private static double mean(List<Integer> idx, IntToDoubleFunction f)
        {
            double sum = 0;
            for (int i : idx)
            {
                sum += f.applyAsDouble(i);
            }
            return idx.isEmpty() ? 0 : sum / idx.size();
        }

        // Same rotation the client applies to models: x' = x cos + z sin, z' = z cos - x sin.
        private double sceneX(double x, double z)
        {
            return baseX + x * cos + z * sin;
        }

        private double sceneY(double x, double z)
        {
            return baseY + z * cos - x * sin;
        }
    }
}
