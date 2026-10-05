package sh.yumekui.toolkit.geom;

import java.util.Arrays;

/**
 * A posed model's triangles in scene space (x, y horizontal, z height above the lowest vertex),
 * plus per-triangle bounding boxes for the broad phase and an exact triangle-triangle intersection
 * test (Möller 1997, "A Fast Triangle-Triangle Intersection Test", interval overlap with the
 * coplanar fallback), for telling whether two actors' models touch and where. Pure: no client state.
 */
public final class TriangleMesh
{
    /** Floats per bounding box: min x, y, z then max x, y, z; and each one's offset in a box. */
    private static final int BOX_FLOATS = 6;
    private static final int MIN_X = 0;
    private static final int MIN_Y = 1;
    private static final int MIN_Z = 2;
    private static final int MAX_X = 3;
    private static final int MAX_Y = 4;
    private static final int MAX_Z = 5;
    /** Vertices per triangle. */
    private static final int CORNERS = 3;
    /** Actor orientation units in a full turn. */
    private static final int FULL_TURN = 2048;
    /** Faces with this transparency are not drawn. */
    private static final int FULLY_TRANSPARENT = 255;
    /** Model face colour 3 of a face the client does not draw. */
    private static final int HIDDEN_FACE = -2;
    /** Plane distances below this (local units) count as on the plane. */
    private static final double EPSILON = 1e-6;
    /** Triangles with a squared normal length below this have no area and never intersect. */
    private static final double DEGENERATE = 1e-12;
    /** Scene-space vertex positions. */
    public final float[] x;
    public final float[] y;
    public final float[] z;
    /** Three vertex indices per triangle. */
    public final int[] faces;
    public final int triangles;
    /** Per triangle: minX, minY, minZ, maxX, maxY, maxZ. */
    final float[] boxes;
    /** Whole-mesh bounds: minX, minY, minZ, maxX, maxY, maxZ. */
    final float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
        -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

    /** Intersecting triangle pairs between two meshes: triangle of a, triangle of b, repeated. */
    public static final class Hits
    {
        public final TriangleMesh a;
        public final TriangleMesh b;
        public final int[] pairs;
        public final int count;

        Hits(TriangleMesh a, TriangleMesh b, int[] pairs, int count)
        {
            this.a = a;
            this.b = b;
            this.pairs = pairs;
            this.count = count;
        }

        /** Scene {x, y, z} centroid of every touching triangle of both meshes. */
        public double[] centroid()
        {
            double sumX = 0;
            double sumY = 0;
            double sumZ = 0;
            for (int i = 0; i < count; i++)
            {
                int ta = pairs[i * 2] * CORNERS;
                int tb = pairs[i * 2 + 1] * CORNERS;
                // Same order as summing every corner in turn, a's then b's, so the result is unchanged.
                for (int k = 0; k < 3; k++)
                {
                    int va = a.faces[ta + k];
                    sumX += a.x[va];
                    sumY += a.y[va];
                    sumZ += a.z[va];
                    int vb = b.faces[tb + k];
                    sumX += b.x[vb];
                    sumY += b.y[vb];
                    sumZ += b.z[vb];
                }
            }
            // Every touching pair contributes both triangles' three corners.
            int n = count * 2 * CORNERS;
            return new double[]{sumX / n, sumY / n, sumZ / n};
        }
    }

    /** Scene-space vertices and triangle indices as given. */
    public TriangleMesh(float[] x, float[] y, float[] z, int[] faces)
    {
        this.x = x;
        this.y = y;
        this.z = z;
        this.faces = faces;
        this.triangles = faces.length / 3;
        this.boxes = new float[triangles * BOX_FLOATS];
        for (int t = 0; t < triangles; t++)
        {
            int at = t * BOX_FLOATS;
            boxes[at] = boxes[at + MIN_Y] = boxes[at + MIN_Z] = Float.MAX_VALUE;
            boxes[at + MAX_X] = boxes[at + MAX_Y] = boxes[at + MAX_Z] = -Float.MAX_VALUE;
            for (int k = 0; k < 3; k++)
            {
                int v = faces[t * CORNERS + k];
                grow(boxes, at, x[v], y[v], z[v]);
            }
            grow(bounds, 0, boxes[at], boxes[at + MIN_Y], boxes[at + MIN_Z]);
            grow(bounds, 0, boxes[at + MAX_X], boxes[at + MAX_Y], boxes[at + MAX_Z]);
        }
    }

    private static void grow(float[] box, int at, float px, float py, float pz)
    {
        box[at] = Math.min(box[at], px);
        box[at + MIN_Y] = Math.min(box[at + MIN_Y], py);
        box[at + MIN_Z] = Math.min(box[at + MIN_Z], pz);
        box[at + MAX_X] = Math.max(box[at + MAX_X], px);
        box[at + MAX_Y] = Math.max(box[at + MAX_Y], py);
        box[at + MAX_Z] = Math.max(box[at + MAX_Z], pz);
    }

    /**
     * Posed model vertices and faces into scene space: rotated by the actor orientation (0-2047 for
     * a full turn), with height above the lowest vertex. Copies everything, so the model may be reused afterwards.
     *
     * @param transparencies per-face transparency or null; fully transparent faces are skipped
     * @param colors3 per-face colour 3 or null; hidden faces (-2) are skipped
     */
    public static TriangleMesh from(float[] xs, float[] ys, float[] zs, int count, int[] f1, int[] f2, int[] f3, int faceCount,
        byte[] transparencies, int[] colors3, int orientation, int baseX, int baseY)
    {
        float bottom = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++)
        {
            bottom = Math.max(bottom, ys[i]);
        }
        double angle = orientation * 2 * Math.PI / FULL_TURN;
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
        int[] faces = new int[faceCount * CORNERS];
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
        return new TriangleMesh(x, y, z, n == faces.length ? faces : Arrays.copyOf(faces, n));
    }

    /** True when the two meshes' whole-mesh bounds overlap (touching counts). */
    public static boolean boundsOverlap(TriangleMesh a, TriangleMesh b)
    {
        for (int k = 0; k < 3; k++)
        {
            if (Math.max(a.bounds[k], b.bounds[k]) > Math.min(a.bounds[k + MAX_X], b.bounds[k + MAX_X]))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * {@link Intersector#intersect} with a throwaway intersector, for tests and one-off callers.
     * Per-frame callers keep one {@link Intersector}, which reuses its buffers.
     */
    static Hits intersect(TriangleMesh a, TriangleMesh b, int limit)
    {
        return new Intersector().intersect(a, b, limit);
    }

    /**
     * Möller's triangle-triangle test on corner arrays, for tests. Closed triangles: touching at an
     * edge or vertex counts. Degenerate (zero-area) triangles never intersect. Coplanar triangles are
     * tested in 2D.
     */
    public static boolean trianglesIntersect(double[] v0, double[] v1, double[] v2, double[] u0, double[] u1, double[] u2)
    {
        Intersector intersector = new Intersector();
        double[] corners = intersector.corners;
        double[][] all = {v0, v1, v2, u0, u1, u2};
        for (int c = 0; c < all.length; c++)
        {
            System.arraycopy(all[c], 0, corners, c * 3, 3);
        }
        return intersector.cornersIntersect();
    }

    /**
     * The narrow phase with its own scratch buffers, so a frame's checks allocate nothing per
     * candidate triangle pair: only a pair that touches gets a {@link Hits} (with its own copy of
     * the pairs). Not thread-safe: one per thread (the client thread keeps one in the tracker).
     */
    public static final class Intersector
    {
        /** The six corners of the pair under test: v0, v1, v2 then u0, u1, u2, x/y/z each. */
        private final double[] corners = new double[18];
        /** Offsets of the corners in {@link #corners}. */
        private static final int V0 = 0;
        private static final int V1 = 3;
        private static final int V2 = 6;
        private static final int U0 = 9;
        private static final int U1 = 12;
        private static final int U2 = 15;
        /** The last {@link #interval}: where a triangle crosses the other's plane, sorted. */
        private double intervalLow;
        private double intervalHigh;

        /** Overlap of the two meshes' bounds. */
        private final float[] region = new float[BOX_FLOATS];
        private int[] candidatesA = new int[0];
        private int[] candidatesB = new int[0];
        private long[] order = new long[0];
        private int[] activeA = new int[0];
        private int[] activeB = new int[0];
        private int[] pairs = new int[16];

        /**
         * Intersecting triangle pairs between a and b, up to {@code limit}. Broad phase: only
         * triangles whose box touches the overlap of the two meshes' bounds. Candidates are paired
         * by sort-and-sweep on x, then box-checked on y and z before the exact test.
         *
         * <p>The sweep stops at {@code limit}, so a limit of 1 answers "touching at all?" for a
         * fraction of the cost of a full count on heavily overlapping models.
         *
         * @param limit most pairs to collect; the work stops there
         * @return the hits, or null when none
         */
        public Hits intersect(TriangleMesh a, TriangleMesh b, int limit)
        {
            if (!overlapRegion(a.bounds, b.bounds))
            {
                return null;
            }
            candidatesA = grow(candidatesA, a.triangles);
            candidatesB = grow(candidatesB, b.triangles);
            int countA = a.trianglesIn(region, candidatesA);
            int countB = b.trianglesIn(region, candidatesB);
            if (countA == 0 || countB == 0)
            {
                return null;
            }
            int entries = sortByMinX(a, countA, b, countB);
            int count = sweep(a, countA, b, countB, entries, limit);
            return count == 0 ? null : new Hits(a, b, Arrays.copyOf(pairs, count * 2), count);
        }

        /** Fills {@link #region} with the overlap of two boxes; false when they don't overlap. */
        private boolean overlapRegion(float[] p, float[] q)
        {
            for (int k = 0; k < 3; k++)
            {
                region[k] = Math.max(p[k], q[k]);
                region[k + MAX_X] = Math.min(p[k + MAX_X], q[k + MAX_X]);
                if (region[k] > region[k + MAX_X])
                {
                    return false;
                }
            }
            return true;
        }

        /**
         * One list of both sides sorted by min x: sortable float bits of min x in the high half, the
         * entry in the low half (b's entries stored as ~t, negative). Primitive sort, no boxing.
         *
         * @return the number of entries in {@link #order}
         */
        private int sortByMinX(TriangleMesh a, int countA, TriangleMesh b, int countB)
        {
            int entries = countA + countB;
            order = grow(order, entries);
            for (int i = 0; i < countA; i++)
            {
                order[i] = sortKey(a.boxes[candidatesA[i] * BOX_FLOATS], candidatesA[i]);
            }
            for (int i = 0; i < countB; i++)
            {
                order[countA + i] = sortKey(b.boxes[candidatesB[i] * BOX_FLOATS], ~candidatesB[i]);
            }
            Arrays.sort(order, 0, entries);
            return entries;
        }

        /**
         * Walks {@link #order}, keeping each side's triangles whose x range is still open, and tests
         * each new triangle against the other side's open ones.
         *
         * @return the number of pairs collected in {@link #pairs}
         */
        private int sweep(TriangleMesh a, int countA, TriangleMesh b, int countB, int entries, int limit)
        {
            activeA = grow(activeA, countA);
            activeB = grow(activeB, countB);
            int openA = 0;
            int openB = 0;
            int count = 0;
            for (int e = 0; e < entries && count < limit; e++)
            {
                int entry = (int) order[e];
                boolean isA = entry >= 0;
                int t = isA ? entry : ~entry;
                TriangleMesh self = isA ? a : b;
                TriangleMesh other = isA ? b : a;
                int[] active = isA ? activeB : activeA;
                int open = isA ? openB : openA;
                float min = self.boxes[t * BOX_FLOATS];
                // Drop the other side's triangles that end before this one starts; test the rest.
                int kept = 0;
                for (int i = 0; i < open; i++)
                {
                    int u = active[i];
                    if (other.boxes[u * BOX_FLOATS + MAX_X] < min)
                    {
                        continue;
                    }
                    active[kept++] = u;
                    if (count < limit && boxesOverlapYZ(self, t, other, u) && trianglesIntersect(self, t, other, u))
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
                    openB = kept;
                    activeA[openA++] = t;
                }
                else
                {
                    openA = kept;
                    activeB[openB++] = t;
                }
            }
            return count;
        }

        /** Triangle t of p against triangle u of q. */
        private boolean trianglesIntersect(TriangleMesh p, int t, TriangleMesh q, int u)
        {
            loadCorners(p, t, V0);
            loadCorners(q, u, U0);
            return cornersIntersect();
        }

        private void loadCorners(TriangleMesh mesh, int triangle, int at)
        {
            for (int k = 0; k < 3; k++)
            {
                int v = mesh.faces[triangle * CORNERS + k];
                corners[at + k * 3] = mesh.x[v];
                corners[at + k * 3 + 1] = mesh.y[v];
                corners[at + k * 3 + 2] = mesh.z[v];
            }
        }

        /**
         * Möller's test on {@link #corners}. Closed triangles: touching at an edge or vertex counts.
         * Degenerate (zero-area) triangles never intersect. Coplanar triangles are tested in 2D.
         * Variable names follow the paper: n1/n2 the planes' normals, d* signed distances.
         */
        private boolean cornersIntersect()
        {
            final double[] c = corners;
            // n1 = (v1 - v0) x (v2 - v0): the normal of v's plane.
            double e1x = c[V1] - c[V0];
            double e1y = c[V1 + 1] - c[V0 + 1];
            double e1z = c[V1 + 2] - c[V0 + 2];
            double e2x = c[V2] - c[V0];
            double e2y = c[V2 + 1] - c[V0 + 1];
            double e2z = c[V2 + 2] - c[V0 + 2];
            double n1x = e1y * e2z - e1z * e2y;
            double n1y = e1z * e2x - e1x * e2z;
            double n1z = e1x * e2y - e1y * e2x;
            double n1len = Math.sqrt(n1x * n1x + n1y * n1y + n1z * n1z);
            // n2 = (u1 - u0) x (u2 - u0): the normal of u's plane.
            double f1x = c[U1] - c[U0];
            double f1y = c[U1 + 1] - c[U0 + 1];
            double f1z = c[U1 + 2] - c[U0 + 2];
            double f2x = c[U2] - c[U0];
            double f2y = c[U2 + 1] - c[U0 + 1];
            double f2z = c[U2 + 2] - c[U0 + 2];
            double n2x = f1y * f2z - f1z * f2y;
            double n2y = f1z * f2x - f1x * f2z;
            double n2z = f1x * f2y - f1y * f2x;
            double n2len = Math.sqrt(n2x * n2x + n2y * n2y + n2z * n2z);
            if (n1len * n1len < DEGENERATE || n2len * n2len < DEGENERATE)
            {
                return false;
            }

            // Signed distances (times |n1|) of u's corners to v's plane.
            double d1 = -(n1x * c[V0] + n1y * c[V0 + 1] + n1z * c[V0 + 2]);
            double du0 = snap(n1x * c[U0] + n1y * c[U0 + 1] + n1z * c[U0 + 2] + d1, n1len);
            double du1 = snap(n1x * c[U1] + n1y * c[U1 + 1] + n1z * c[U1 + 2] + d1, n1len);
            double du2 = snap(n1x * c[U2] + n1y * c[U2 + 1] + n1z * c[U2 + 2] + d1, n1len);
            double du0du1 = du0 * du1;
            double du0du2 = du0 * du2;
            if (du0du1 > 0 && du0du2 > 0)
            {
                return false;
            }

            // Signed distances (times |n2|) of v's corners to u's plane.
            double d2 = -(n2x * c[U0] + n2y * c[U0 + 1] + n2z * c[U0 + 2]);
            double dv0 = snap(n2x * c[V0] + n2y * c[V0 + 1] + n2z * c[V0 + 2] + d2, n2len);
            double dv1 = snap(n2x * c[V1] + n2y * c[V1 + 1] + n2z * c[V1 + 2] + d2, n2len);
            double dv2 = snap(n2x * c[V2] + n2y * c[V2 + 1] + n2z * c[V2 + 2] + d2, n2len);
            double dv0dv1 = dv0 * dv1;
            double dv0dv2 = dv0 * dv2;
            if (dv0dv1 > 0 && dv0dv2 > 0)
            {
                return false;
            }

            if (du0 == 0 && du1 == 0 && du2 == 0)
            {
                return coplanar(n1x, n1y, n1z);
            }

            // Project onto the largest axis of the intersection line direction, n1 x n2.
            double dirX = Math.abs(n1y * n2z - n1z * n2y);
            double dirY = Math.abs(n1z * n2x - n1x * n2z);
            double dirZ = Math.abs(n1x * n2y - n1y * n2x);
            int axis = dirX >= dirY && dirX >= dirZ ? 0 : dirY >= dirZ ? 1 : 2;
            if (!interval(c[V0 + axis], c[V1 + axis], c[V2 + axis], dv0, dv1, dv2, dv0dv1, dv0dv2))
            {
                return coplanar(n1x, n1y, n1z);
            }
            double vLow = intervalLow;
            double vHigh = intervalHigh;
            if (!interval(c[U0 + axis], c[U1 + axis], c[U2 + axis], du0, du1, du2, du0du1, du0du2))
            {
                return coplanar(n1x, n1y, n1z);
            }
            return !(vHigh < intervalLow || intervalHigh < vLow);
        }

        /** A plane distance (times the normal's length) close enough to zero counts as on the plane. */
        private static double snap(double distanceTimesLength, double length)
        {
            return Math.abs(distanceTimesLength) < EPSILON * length ? 0 : distanceTimesLength;
        }

        /**
         * Where the triangle crosses the other's plane, as a sorted interval on the projected line
         * ({@link #intervalLow}..{@link #intervalHigh}): from the lone vertex on one side towards the
         * other two. False when it lies in the plane.
         */
        private boolean interval(double p0, double p1, double p2, double d0, double d1, double d2,
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
            return false;
        }

        /**
         * Interval from lone vertex (p, d) along its edges to (q, dq) and (r, dr), in the paper's names:
         * p, q, r are the projected corners and d, dq, dr their signed distances. Always true.
         */
        private boolean span(double p, double q, double r, double d, double dq, double dr)
        {
            double towardsQ = p + (q - p) * d / (d - dq);
            double towardsR = p + (r - p) * d / (d - dr);
            boolean ordered = towardsQ <= towardsR;
            intervalLow = ordered ? towardsQ : towardsR;
            intervalHigh = ordered ? towardsR : towardsQ;
            return true;
        }

        /** Coplanar test in the axis-aligned plane where the triangles have the largest area. */
        private boolean coplanar(double nx, double ny, double nz)
        {
            double ax = Math.abs(nx);
            double ay = Math.abs(ny);
            double az = Math.abs(nz);
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
            for (int i = 0; i < 3; i++)
            {
                for (int j = 0; j < 3; j++)
                {
                    if (segmentsCross(V0 + i * 3, V0 + (i + 1) % 3 * 3, U0 + j * 3, U0 + (j + 1) % 3 * 3, i0, i1))
                    {
                        return true;
                    }
                }
            }
            return pointInTriangle(V0, U0, U1, U2, i0, i1) || pointInTriangle(U0, V0, V1, V2, i0, i1);
        }

        /**
         * 2D segment p0-p1 against q0-q1, all offsets into {@link #corners} (Möller's EDGE_EDGE_TEST,
         * endpoints inclusive; ax..e are the paper's names).
         */
        private boolean segmentsCross(int p0, int p1, int q0, int q1, int i0, int i1)
        {
            final double[] c = corners;
            double ax = c[p1 + i0] - c[p0 + i0];
            double ay = c[p1 + i1] - c[p0 + i1];
            double bx = c[q0 + i0] - c[q1 + i0];
            double by = c[q0 + i1] - c[q1 + i1];
            double cx = c[p0 + i0] - c[q0 + i0];
            double cy = c[p0 + i1] - c[q0 + i1];
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
        private boolean pointInTriangle(int p, int t0, int t1, int t2, int i0, int i1)
        {
            double e0 = edge(p, t0, t1, i0, i1);
            double e1 = edge(p, t1, t2, i0, i1);
            double e2 = edge(p, t2, t0, i0, i1);
            return e0 * e1 > 0 && e0 * e2 > 0;
        }

        /** Which side of the line a-b point p is on (the sign), from the line's implicit equation. */
        private double edge(int p, int a, int b, int i0, int i1)
        {
            final double[] c = corners;
            double ea = c[b + i1] - c[a + i1];
            double eb = -(c[b + i0] - c[a + i0]);
            double ec = -ea * c[a + i0] - eb * c[a + i1];
            return ea * c[p + i0] + eb * c[p + i1] + ec;
        }

        private static int[] grow(int[] buffer, int size)
        {
            return buffer.length >= size ? buffer : new int[Math.max(size, buffer.length * 2)];
        }

        private static long[] grow(long[] buffer, int size)
        {
            return buffer.length >= size ? buffer : new long[Math.max(size, buffer.length * 2)];
        }
    }

    /** True when the triangle's box overlaps the given {minX, minY, minZ, maxX, maxY, maxZ} box. */
    private boolean boxTouches(int t, float[] box)
    {
        int at = t * BOX_FLOATS;
        return boxes[at] <= box[MAX_X] && boxes[at + MAX_X] >= box[MIN_X]
            && boxes[at + MIN_Y] <= box[MAX_Y] && boxes[at + MAX_Y] >= box[MIN_Y]
            && boxes[at + MIN_Z] <= box[MAX_Z] && boxes[at + MAX_Z] >= box[MIN_Z];
    }

    /** Writes the triangles of this mesh whose box touches the region into {@code out}; returns how many. */
    private int trianglesIn(float[] region, int[] out)
    {
        int n = 0;
        for (int t = 0; t < triangles; t++)
        {
            if (boxTouches(t, region))
            {
                out[n++] = t;
            }
        }
        return n;
    }

    /** Orders by min x (float bits flipped so negative values sort below positive), then entry. */
    private static long sortKey(float minX, int entry)
    {
        int bits = Float.floatToIntBits(minX);
        bits ^= (bits >> 31) & 0x7fffffff;
        return (long) bits << 32 | (entry & 0xffffffffL);
    }

    private static boolean boxesOverlapYZ(TriangleMesh self, int t, TriangleMesh other, int u)
    {
        int at = t * BOX_FLOATS;
        int otherAt = u * BOX_FLOATS;
        return self.boxes[at + MIN_Y] <= other.boxes[otherAt + MAX_Y] && self.boxes[at + MAX_Y] >= other.boxes[otherAt + MIN_Y]
            && self.boxes[at + MIN_Z] <= other.boxes[otherAt + MAX_Z] && self.boxes[at + MAX_Z] >= other.boxes[otherAt + MIN_Z];
    }
}
