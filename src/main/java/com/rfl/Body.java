package com.rfl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A player's posed model split into capsules: two legs, torso, two arms, head. Replaces the old
 * whole-body cylinder, which widened whenever a catch animation stretched the arms and so touched
 * neighbours who weren't touching.
 */
final class Body
{
    /** Vertices below this fraction of the model height are legs. */
    static final double LEG_TOP = 0.45;
    /** Vertices above this fraction of the model height are the head. */
    static final double HEAD_BOTTOM = 0.85;
    /**
     * Between legs and head, vertices within this fraction of the model height of the band's mean
     * x are torso; the rest are arms. Initial value; tune against the hitbox overlay in game.
     */
    static final double TORSO_HALF_WIDTH = 0.15;
    /** Arm radius (local units); an arm's vertex spread says little about its thickness. */
    static final double ARM_RADIUS = 10;

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

        double angle = orientation * 2 * Math.PI / 2048;
        Frame frame = new Frame(xs, h, zs, Math.sin(angle), Math.cos(angle), baseX, baseY);
        List<Capsule> parts = new ArrayList<>();
        frame.upright("leftLeg", leftLeg, parts);
        frame.upright("rightLeg", rightLeg, parts);
        Capsule torsoPart = frame.upright("torso", torso, parts);
        frame.arm("leftArm", leftArm, parts);
        frame.arm("rightArm", rightArm, parts);
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
         * Capsule between the arm's two farthest-apart vertices, approximated as the vertex
         * farthest from the centroid, then the vertex farthest from that one.
         */
        void arm(String name, List<Integer> idx, List<Capsule> out)
        {
            if (idx.isEmpty())
            {
                return;
            }
            double cx = 0;
            double ch = 0;
            double cz = 0;
            for (int i : idx)
            {
                cx += xs[i];
                ch += h[i];
                cz += zs[i];
            }
            int p = farthest(idx, cx / idx.size(), ch / idx.size(), cz / idx.size());
            int q = farthest(idx, xs[p], h[p], zs[p]);
            out.add(new Capsule(name,
                sceneX(xs[p], zs[p]), sceneY(xs[p], zs[p]), h[p],
                sceneX(xs[q], zs[q]), sceneY(xs[q], zs[q]), h[q], ARM_RADIUS));
        }

        private int farthest(List<Integer> idx, double x, double y, double z)
        {
            int best = idx.get(0);
            double bestD = -1;
            for (int i : idx)
            {
                double d = (xs[i] - x) * (xs[i] - x) + (h[i] - y) * (h[i] - y) + (zs[i] - z) * (zs[i] - z);
                if (d > bestD)
                {
                    bestD = d;
                    best = i;
                }
            }
            return best;
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
