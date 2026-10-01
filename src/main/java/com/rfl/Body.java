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
     * Leg vertices lower than this (local units above the soles) are the foot: fitted on its own
     * so it lies flat heel to toe, and the shin stops at the ankle.
     */
    static final double FOOT_TOP = 16;
    /** Vertices this far beyond a leg segment's height span still count toward its radius. */
    static final double LEG_RADIUS_MARGIN = 4;
    /** Vertices above this fraction of the model height are the head. */
    static final double HEAD_BOTTOM = 0.85;
    /**
     * Between legs and head, vertices within this fraction of the model height of the band's mean
     * x are torso; the rest are arms. Initial value; tune against the hitbox overlay in game.
     */
    static final double TORSO_HALF_WIDTH = 0.15;
    /** Limb segment radius = this percentile of the vertices' distances from the segment axis. */
    static final double LIMB_RADIUS_PERCENTILE = 0.8;
    /** Limb segment radius clamp (local units). */
    static final double MIN_LIMB_RADIUS = 4;
    static final double MAX_LIMB_RADIUS = 30;
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
        List<Integer> leftFoot = new ArrayList<>();
        List<Integer> rightFoot = new ArrayList<>();
        List<Integer> torso = new ArrayList<>();
        List<Integer> leftArm = new ArrayList<>();
        List<Integer> rightArm = new ArrayList<>();
        List<Integer> head = new ArrayList<>();
        for (int i = 0; i < count; i++)
        {
            if (h[i] < FOOT_TOP)
            {
                (xs[i] < legMean ? leftFoot : rightFoot).add(i);
            }
            else if (h[i] < legTop)
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

        double angle = orientation * 2 * Math.PI / 2048;
        Frame frame = new Frame(xs, h, zs, Math.sin(angle), Math.cos(angle), baseX, baseY);
        double torsoX = 0;
        double torsoZ = 0;
        for (int i : torso)
        {
            torsoX += xs[i];
            torsoZ += zs[i];
        }
        double tx = torso.isEmpty() ? bandMean : torsoX / torso.size();
        double tz = torso.isEmpty() ? 0 : torsoZ / torso.size();
        // Proximal half: higher for legs, nearer the torso's vertical centre line for arms.
        IntToDoubleFunction armProximity = i -> -Math.hypot(xs[i] - tx, zs[i] - tz);

        List<Capsule> parts = new ArrayList<>();
        frame.leg("leftThigh", "leftShin", leftLeg, parts);
        frame.segment("leftFoot", leftFoot, parts);
        frame.leg("rightThigh", "rightShin", rightLeg, parts);
        frame.segment("rightFoot", rightFoot, parts);
        Capsule torsoPart = frame.upright("torso", torso, parts);
        frame.limb("leftUpperArm", "leftForearm", leftArm, armProximity, parts);
        frame.limb("rightUpperArm", "rightForearm", rightArm, armProximity, parts);
        frame.upright("head", head, parts);

        if (torsoPart == null)
        {
            return new Body(parts, baseX, baseY);
        }
        return new Body(parts, (int) Math.round(torsoPart.ax), (int) Math.round(torsoPart.ay));
    }

    /** Model-space vertices (with height above the soles) plus the transform into scene space. */
    private static final class Frame
    {
        final float[] xs;
        final float[] h;
        final float[] zs;
        final double sin;
        final double cos;
        final int baseX;
        final int baseY;

        Frame(float[] xs, float[] h, float[] zs, double sin, double cos, int baseX, int baseY)
        {
            this.xs = xs;
            this.h = h;
            this.zs = zs;
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
            double radius = ((maxX - minX) / 2.0 + (maxZ - minZ) / 2.0) / 2;
            double sx = sceneX(x, z);
            double sy = sceneY(x, z);
            Capsule c = new Capsule(name, sx, sy, minH, sx, sy, maxH, radius);
            out.add(c);
            return c;
        }

        /**
         * A leg as shin and thigh from ankle to knee to hip, where each point is the mean of a
         * height slice of the leg: lowest quarter (ankle), middle third (knee), highest quarter
         * (hip). OSRS legs are a handful of vertices, so single rings sit off-centre; averaging
         * slices keeps the shin and thigh along the leg instead of across the hips.
         */
        void leg(String thigh, String shin, List<Integer> idx, List<Capsule> out)
        {
            if (idx.size() < 4)
            {
                segment(thigh, idx, out);
                return;
            }
            List<Integer> sorted = new ArrayList<>(idx);
            sorted.sort(Comparator.comparingDouble(i -> h[i]));
            int n = sorted.size();
            int q = Math.max(1, n / 4);
            double[] knee = centre(sorted.subList(n / 3, Math.max(n / 3 + 1, 2 * n / 3)));
            // Extend each slice mean out along its segment to the leg's lowest and highest
            // vertex, so the shin meets the foot and the thigh reaches the hip.
            double[] ankle = atHeight(knee, centre(sorted.subList(0, q)), h[sorted.get(0)]);
            double[] hip = atHeight(knee, centre(sorted.subList(n - q, n)), h[sorted.get(n - 1)]);
            between(shin, ankle, knee, idx, out);
            between(thigh, knee, hip, idx, out);
        }

        /** The point on the line from {@code from} through {@code to} at the given height. */
        private static double[] atHeight(double[] from, double[] to, double height)
        {
            double dh = to[1] - from[1];
            if (Math.abs(dh) < 1e-6)
            {
                return to;
            }
            double t = (height - from[1]) / dh;
            return new double[]{from[0] + t * (to[0] - from[0]), height, from[2] + t * (to[2] - from[2])};
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

        /**
         * Capsule from {@code a} to {@code b} (model x, height, z). Radius: the
         * {@link #LIMB_RADIUS_PERCENTILE} distance from that line of the vertices within its height
         * span, clamped.
         */
        private void between(String name, double[] a, double[] b, List<Integer> idx, List<Capsule> out)
        {
            double lo = Math.min(a[1], b[1]);
            double hi = Math.max(a[1], b[1]);
            double dx = b[0] - a[0];
            double dh = b[1] - a[1];
            double dz = b[2] - a[2];
            double len2 = dx * dx + dh * dh + dz * dz;
            List<Double> dist = new ArrayList<>();
            for (int i : idx)
            {
                if (h[i] < lo - LEG_RADIUS_MARGIN || h[i] > hi + LEG_RADIUS_MARGIN)
                {
                    continue;
                }
                double t = len2 == 0 ? 0 : ((xs[i] - a[0]) * dx + (h[i] - a[1]) * dh + (zs[i] - a[2]) * dz) / len2;
                t = Math.max(0, Math.min(1, t));
                double px = xs[i] - (a[0] + t * dx);
                double ph = h[i] - (a[1] + t * dh);
                double pz = zs[i] - (a[2] + t * dz);
                dist.add(Math.sqrt(px * px + ph * ph + pz * pz));
            }
            double radius = MIN_LIMB_RADIUS;
            if (!dist.isEmpty())
            {
                dist.sort(null);
                radius = dist.get((int) Math.ceil(LIMB_RADIUS_PERCENTILE * dist.size()) - 1);
            }
            radius = Math.max(MIN_LIMB_RADIUS, Math.min(MAX_LIMB_RADIUS, radius));
            out.add(new Capsule(name, sceneX(a[0], a[2]), sceneY(a[0], a[2]), a[1],
                sceneX(b[0], b[2]), sceneY(b[0], b[2]), b[1], radius));
        }

        /**
         * Splits the limb at the median projection on its principal axis and adds a capsule per
         * half, proximal half first.
         */
        void limb(String proximal, String distal, List<Integer> idx, IntToDoubleFunction proximity, List<Capsule> out)
        {
            if (idx.isEmpty())
            {
                return;
            }
            double[] axis = axis(idx);
            List<Integer> sorted = new ArrayList<>(idx);
            sorted.sort(Comparator.comparingDouble(i -> project(i, axis)));
            List<Integer> lo = sorted.subList(0, sorted.size() / 2);
            List<Integer> hi = sorted.subList(sorted.size() / 2, sorted.size());
            boolean loProximal = mean(lo, proximity) >= mean(hi, proximity);
            segment(proximal, loProximal ? lo : hi, out);
            segment(distal, loProximal ? hi : lo, out);
        }

        /**
         * Capsule along the segment's own principal axis: endpoints at the min/max projection,
         * radius = {@link #LIMB_RADIUS_PERCENTILE} of the distances from that axis, clamped.
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
            Arrays.sort(dist);
            double radius = dist[(int) Math.ceil(LIMB_RADIUS_PERCENTILE * dist.length) - 1];
            radius = Math.max(MIN_LIMB_RADIUS, Math.min(MAX_LIMB_RADIUS, radius));
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
