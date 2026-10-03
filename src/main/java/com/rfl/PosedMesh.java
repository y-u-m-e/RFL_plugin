package com.rfl;

import java.util.Arrays;

/**
 * A player's posed model triangles in scene space (x, y horizontal, z height above the soles),
 * plus per-triangle bounding boxes for the broad phase and
 * a triangle-triangle intersection test (Möller 1997, "A Fast Triangle-Triangle Intersection
 * Test", interval overlap with the coplanar fallback). Pure: no client state.
 */
final class PosedMesh
{
    /** Faces with this transparency are not drawn. */
    private static final int FULLY_TRANSPARENT = 255;
    /** Model face colour 3 of a face the client does not draw. */
    private static final int HIDDEN_FACE = -2;
    /** Plane distances below this (local units) count as on the plane. */
    private static final double EPSILON = 1e-6;
    /** Triangles with a squared normal length below this have no area and never intersect. */
    private static final double DEGENERATE = 1e-12;
    /**
     * At most this many intersecting triangle pairs are collected per player pair. The count is
     * saved as a collision's maxTriangles, so a value of MAX_HITS means "at least MAX_HITS".
     */
    static final int MAX_HITS = 512;

    /** Scene-space vertex positions. */
    final float[] x;
    final float[] y;
    final float[] z;
    /** Three vertex indices per triangle. */
    final int[] faces;
    final int triangles;
    /** Per triangle: minX, minY, minZ, maxX, maxY, maxZ. */
    final float[] boxes;
    /** Whole-mesh bounds: minX, minY, minZ, maxX, maxY, maxZ. */
    final float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
        -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

    /** Intersecting triangle pairs between two meshes: triangle of a, triangle of b, repeated. */
    static final class Hits
    {
        final PosedMesh a;
        final PosedMesh b;
        final int[] pairs;
        final int count;

        Hits(PosedMesh a, PosedMesh b, int[] pairs, int count)
        {
            this.a = a;
            this.b = b;
            this.pairs = pairs;
            this.count = count;
        }

        /** Scene {x, y, z} centroid of every touching triangle of both meshes. */
        double[] centroid()
        {
            double[] sum = new double[3];
            for (int i = 0; i < count; i++)
            {
                for (int k = 0; k < 3; k++)
                {
                    add(sum, a.corner(pairs[i * 2], k));
                    add(sum, b.corner(pairs[i * 2 + 1], k));
                }
            }
            int n = count * 6;
            return new double[]{sum[0] / n, sum[1] / n, sum[2] / n};
        }

        private static void add(double[] sum, double[] p)
        {
            sum[0] += p[0];
            sum[1] += p[1];
            sum[2] += p[2];
        }
    }

    /** Scene-space vertices and triangle indices as given. */
    PosedMesh(float[] x, float[] y, float[] z, int[] faces)
    {
        this.x = x;
        this.y = y;
        this.z = z;
        this.faces = faces;
        this.triangles = faces.length / 3;
        this.boxes = new float[triangles * 6];
        for (int t = 0; t < triangles; t++)
        {
            int o = t * 6;
            boxes[o] = boxes[o + 1] = boxes[o + 2] = Float.MAX_VALUE;
            boxes[o + 3] = boxes[o + 4] = boxes[o + 5] = -Float.MAX_VALUE;
            for (int k = 0; k < 3; k++)
            {
                int v = faces[t * 3 + k];
                grow(boxes, o, x[v], y[v], z[v]);
            }
            grow(bounds, 0, boxes[o], boxes[o + 1], boxes[o + 2]);
            grow(bounds, 0, boxes[o + 3], boxes[o + 4], boxes[o + 5]);
        }
    }

    private static void grow(float[] box, int o, float px, float py, float pz)
    {
        box[o] = Math.min(box[o], px);
        box[o + 1] = Math.min(box[o + 1], py);
        box[o + 2] = Math.min(box[o + 2], pz);
        box[o + 3] = Math.max(box[o + 3], px);
        box[o + 4] = Math.max(box[o + 4], py);
        box[o + 5] = Math.max(box[o + 5], pz);
    }

    /**
     * Posed model vertices and faces into scene space: rotated by the actor orientation (0-2047 for
     * a full turn), with height above the lowest vertex. Copies everything, so the model may be reused afterwards.
     *
     * @param transparencies per-face transparency or null; fully transparent faces are skipped
     * @param colors3 per-face colour 3 or null; hidden faces (-2) are skipped
     */
    static PosedMesh from(float[] xs, float[] ys, float[] zs, int count, int[] f1, int[] f2, int[] f3, int faceCount,
        byte[] transparencies, int[] colors3, int orientation, int baseX, int baseY)
    {
        float bottom = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++)
        {
            bottom = Math.max(bottom, ys[i]);
        }
        double angle = orientation * 2 * Math.PI / 2048;
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        float[] x = new float[count];
        float[] y = new float[count];
        float[] z = new float[count];
        for (int i = 0; i < count; i++)
        {
            // Same rotation the client applies to models: x' = x cos + z sin, z' = z cos - x sin.
            x[i] = (float) (baseX + xs[i] * cos + zs[i] * sin);
            y[i] = (float) (baseY + zs[i] * cos - xs[i] * sin);
            z[i] = bottom - ys[i];
        }
        int[] faces = new int[faceCount * 3];
        int n = 0;
        for (int f = 0; f < faceCount; f++)
        {
            if (transparencies != null && (transparencies[f] & 0xFF) == FULLY_TRANSPARENT
                || colors3 != null && colors3[f] == HIDDEN_FACE)
            {
                continue;
            }
            int a = f1[f];
            int b = f2[f];
            int c = f3[f];
            if (a < 0 || b < 0 || c < 0 || a >= count || b >= count || c >= count)
            {
                continue;
            }
            faces[n++] = a;
            faces[n++] = b;
            faces[n++] = c;
        }
        return new PosedMesh(x, y, z, n == faces.length ? faces : Arrays.copyOf(faces, n));
    }

    /** Scene position {x, y, z} of corner k (0-2) of triangle t. */
    double[] corner(int t, int k)
    {
        int v = faces[t * 3 + k];
        return new double[]{x[v], y[v], z[v]};
    }

    /** True when the triangle's box overlaps the given {minX, minY, minZ, maxX, maxY, maxZ} box. */
    private boolean boxTouches(int t, float[] box)
    {
        int o = t * 6;
        return boxes[o] <= box[3] && boxes[o + 3] >= box[0]
            && boxes[o + 1] <= box[4] && boxes[o + 4] >= box[1]
            && boxes[o + 2] <= box[5] && boxes[o + 5] >= box[2];
    }

    /** Triangles of this mesh whose box touches the region. */
    private int[] trianglesIn(float[] region)
    {
        int[] out = new int[triangles];
        int n = 0;
        for (int t = 0; t < triangles; t++)
        {
            if (boxTouches(t, region))
            {
                out[n++] = t;
            }
        }
        return Arrays.copyOf(out, n);
    }

    /**
     * Intersecting triangle pairs between a and b, up to {@code limit}. Broad phase: only
     * triangles whose box touches the overlap of the two meshes' bounds. Candidates are paired by
     * sort-and-sweep on x, then box-checked on y and z before the exact test.
     *
     * <p>The sweep stops at {@code limit}, so a limit of 1 answers "touching at all?" for a
     * fraction of the cost of a full count on heavily overlapping models.
     *
     * @param limit most pairs to collect, at most {@link #MAX_HITS}
     * @return the hits, or null when none
     */
    static Hits intersect(PosedMesh a, PosedMesh b, int limit)
    {
        float[] overlap = overlap(a.bounds, b.bounds);
        if (overlap == null)
        {
            return null;
        }
        int[] ta = a.trianglesIn(overlap);
        int[] tb = b.trianglesIn(overlap);
        if (ta.length == 0 || tb.length == 0)
        {
            return null;
        }

        // One list of both sides sorted by min x: sortable float bits of min x in the high half,
        // the entry in the low half (b's entries stored as ~t, negative). Primitive sort, no boxing.
        long[] order = new long[ta.length + tb.length];
        for (int i = 0; i < ta.length; i++)
        {
            order[i] = sortKey(a.boxes[ta[i] * 6], ta[i]);
        }
        for (int i = 0; i < tb.length; i++)
        {
            order[ta.length + i] = sortKey(b.boxes[tb[i] * 6], ~tb[i]);
        }
        Arrays.sort(order);

        int[] activeA = new int[ta.length];
        int[] activeB = new int[tb.length];
        int na = 0;
        int nb = 0;
        int[] pairs = new int[16];
        int count = 0;
        for (long key : order)
        {
            if (count >= limit)
            {
                break;
            }
            int e = (int) key;
            boolean isA = e >= 0;
            int t = isA ? e : ~e;
            PosedMesh self = isA ? a : b;
            PosedMesh other = isA ? b : a;
            int[] active = isA ? activeB : activeA;
            int n = isA ? nb : na;
            float min = self.boxes[t * 6];
            // Drop the other side's triangles that end before this one starts; test the rest.
            int kept = 0;
            for (int i = 0; i < n; i++)
            {
                int u = active[i];
                if (other.boxes[u * 6 + 3] < min)
                {
                    continue;
                }
                active[kept++] = u;
                if (count < limit && boxesOverlapYZ(self, t, other, u)
                    && trianglesIntersect(self.corner(t, 0), self.corner(t, 1), self.corner(t, 2),
                    other.corner(u, 0), other.corner(u, 1), other.corner(u, 2)))
                {
                    if (count * 2 == pairs.length)
                    {
                        pairs = Arrays.copyOf(pairs, pairs.length * 2);
                    }
                    pairs[count * 2] = isA ? t : u;
                    pairs[count * 2 + 1] = isA ? u : t;
                    count++;
                }
            }
            if (isA)
            {
                nb = kept;
                activeA[na++] = t;
            }
            else
            {
                na = kept;
                activeB[nb++] = t;
            }
        }
        return count == 0 ? null : new Hits(a, b, pairs, count);
    }

    /** Orders by min x (float bits flipped so negative values sort below positive), then entry. */
    private static long sortKey(float minX, int entry)
    {
        int bits = Float.floatToIntBits(minX);
        bits ^= (bits >> 31) & 0x7fffffff;
        return (long) bits << 32 | (entry & 0xffffffffL);
    }

    private static boolean boxesOverlapYZ(PosedMesh p, int t, PosedMesh q, int u)
    {
        int o = t * 6;
        int r = u * 6;
        return p.boxes[o + 1] <= q.boxes[r + 4] && p.boxes[o + 4] >= q.boxes[r + 1]
            && p.boxes[o + 2] <= q.boxes[r + 5] && p.boxes[o + 5] >= q.boxes[r + 2];
    }

    /** Overlap of two {min.., max..} boxes, or null when they don't overlap. */
    static float[] overlap(float[] p, float[] q)
    {
        float[] r = new float[6];
        for (int k = 0; k < 3; k++)
        {
            r[k] = Math.max(p[k], q[k]);
            r[k + 3] = Math.min(p[k + 3], q[k + 3]);
            if (r[k] > r[k + 3])
            {
                return null;
            }
        }
        return r;
    }

    /**
     * Möller's triangle-triangle test. Closed triangles: touching at an edge or vertex counts.
     * Degenerate (zero-area) triangles never intersect. Coplanar triangles are tested in 2D.
     */
    static boolean trianglesIntersect(double[] v0, double[] v1, double[] v2, double[] u0, double[] u1, double[] u2)
    {
        double[] n1 = cross(sub(v1, v0), sub(v2, v0));
        double n1len = Math.sqrt(dot(n1, n1));
        double[] n2 = cross(sub(u1, u0), sub(u2, u0));
        double n2len = Math.sqrt(dot(n2, n2));
        if (n1len * n1len < DEGENERATE || n2len * n2len < DEGENERATE)
        {
            return false;
        }

        // Signed distances (times |n1|) of u's corners to v's plane.
        double d1 = -dot(n1, v0);
        double du0 = snap(dot(n1, u0) + d1, n1len);
        double du1 = snap(dot(n1, u1) + d1, n1len);
        double du2 = snap(dot(n1, u2) + d1, n1len);
        double du0du1 = du0 * du1;
        double du0du2 = du0 * du2;
        if (du0du1 > 0 && du0du2 > 0)
        {
            return false;
        }

        double d2 = -dot(n2, u0);
        double dv0 = snap(dot(n2, v0) + d2, n2len);
        double dv1 = snap(dot(n2, v1) + d2, n2len);
        double dv2 = snap(dot(n2, v2) + d2, n2len);
        double dv0dv1 = dv0 * dv1;
        double dv0dv2 = dv0 * dv2;
        if (dv0dv1 > 0 && dv0dv2 > 0)
        {
            return false;
        }

        if (du0 == 0 && du1 == 0 && du2 == 0)
        {
            return coplanar(n1, v0, v1, v2, u0, u1, u2);
        }

        // Project onto the largest axis of the intersection line direction.
        double[] dir = cross(n1, n2);
        int axis = Math.abs(dir[0]) >= Math.abs(dir[1]) && Math.abs(dir[0]) >= Math.abs(dir[2]) ? 0
            : Math.abs(dir[1]) >= Math.abs(dir[2]) ? 1 : 2;
        double[] iv = interval(v0[axis], v1[axis], v2[axis], dv0, dv1, dv2, dv0dv1, dv0dv2);
        double[] iu = interval(u0[axis], u1[axis], u2[axis], du0, du1, du2, du0du1, du0du2);
        if (iv == null || iu == null)
        {
            return coplanar(n1, v0, v1, v2, u0, u1, u2);
        }
        return !(iv[1] < iu[0] || iu[1] < iv[0]);
    }

    private static double snap(double distanceTimesLength, double length)
    {
        return Math.abs(distanceTimesLength) < EPSILON * length ? 0 : distanceTimesLength;
    }

    /**
     * Where the triangle crosses the other's plane, as a sorted interval on the projected line:
     * from the lone vertex on one side towards the other two. Null when it lies in the plane.
     */
    private static double[] interval(double p0, double p1, double p2, double d0, double d1, double d2,
        double d0d1, double d0d2)
    {
        if (d0d1 > 0)
        {
            return span(p2, p0, p1, d2, d0, d1);
        }
        if (d0d2 > 0)
        {
            return span(p1, p0, p2, d1, d0, d2);
        }
        if (d1 * d2 > 0 || d0 != 0)
        {
            return span(p0, p1, p2, d0, d1, d2);
        }
        if (d1 != 0)
        {
            return span(p1, p0, p2, d1, d0, d2);
        }
        if (d2 != 0)
        {
            return span(p2, p0, p1, d2, d0, d1);
        }
        return null;
    }

    /** Interval from lone vertex (p, d) along its edges to (q, dq) and (r, dr). */
    private static double[] span(double p, double q, double r, double d, double dq, double dr)
    {
        double a = p + (q - p) * d / (d - dq);
        double b = p + (r - p) * d / (d - dr);
        return a <= b ? new double[]{a, b} : new double[]{b, a};
    }

    /** Coplanar test in the axis-aligned plane where the triangles have the largest area. */
    private static boolean coplanar(double[] n, double[] v0, double[] v1, double[] v2,
        double[] u0, double[] u1, double[] u2)
    {
        double ax = Math.abs(n[0]);
        double ay = Math.abs(n[1]);
        double az = Math.abs(n[2]);
        int i0;
        int i1;
        if (ax > ay)
        {
            if (ax > az)
            {
                i0 = 1;
                i1 = 2;
            }
            else
            {
                i0 = 0;
                i1 = 1;
            }
        }
        else if (az > ay)
        {
            i0 = 0;
            i1 = 1;
        }
        else
        {
            i0 = 0;
            i1 = 2;
        }
        double[][] v = {v0, v1, v2};
        double[][] u = {u0, u1, u2};
        for (int i = 0; i < 3; i++)
        {
            for (int j = 0; j < 3; j++)
            {
                if (segmentsCross(v[i], v[(i + 1) % 3], u[j], u[(j + 1) % 3], i0, i1))
                {
                    return true;
                }
            }
        }
        return pointInTriangle(v0, u0, u1, u2, i0, i1) || pointInTriangle(u0, v0, v1, v2, i0, i1);
    }

    /** 2D segment p0-p1 against q0-q1 (Möller's EDGE_EDGE_TEST, endpoints inclusive). */
    private static boolean segmentsCross(double[] p0, double[] p1, double[] q0, double[] q1, int i0, int i1)
    {
        double ax = p1[i0] - p0[i0];
        double ay = p1[i1] - p0[i1];
        double bx = q0[i0] - q1[i0];
        double by = q0[i1] - q1[i1];
        double cx = p0[i0] - q0[i0];
        double cy = p0[i1] - q0[i1];
        double f = ay * bx - ax * by;
        double d = by * cx - bx * cy;
        if (f > 0 && d >= 0 && d <= f || f < 0 && d <= 0 && d >= f)
        {
            double e = ax * cy - ay * cx;
            return f > 0 ? e >= 0 && e <= f : e <= 0 && e >= f;
        }
        return false;
    }

    /** 2D point p strictly inside triangle t0 t1 t2 (edges are covered by segmentsCross). */
    private static boolean pointInTriangle(double[] p, double[] t0, double[] t1, double[] t2, int i0, int i1)
    {
        double e0 = edge(p, t0, t1, i0, i1);
        double e1 = edge(p, t1, t2, i0, i1);
        double e2 = edge(p, t2, t0, i0, i1);
        return e0 * e1 > 0 && e0 * e2 > 0;
    }

    private static double edge(double[] p, double[] a, double[] b, int i0, int i1)
    {
        double ea = b[i1] - a[i1];
        double eb = -(b[i0] - a[i0]);
        double ec = -ea * a[i0] - eb * a[i1];
        return ea * p[i0] + eb * p[i1] + ec;
    }

    private static double[] sub(double[] a, double[] b)
    {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double dot(double[] a, double[] b)
    {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b)
    {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
