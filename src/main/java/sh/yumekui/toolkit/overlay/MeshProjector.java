package sh.yumekui.toolkit.overlay;

import sh.yumekui.toolkit.geom.TriangleMesh;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws {@link TriangleMesh} triangles on screen cheaply: a vertex is shared by about six triangles,
 * so each mesh's vertices are projected at most once per frame, on first use, into buffers reused
 * from frame to frame, and each triangle comes out through one reused {@link Polygon}.
 *
 * <p>Call {@link #startFrame} at the top of a render, then {@link #triangle} for each triangle.
 * Not thread-safe: overlays render on the client thread.
 */
public final class MeshProjector
{
    /** Projects one scene-space vertex to the canvas. */
    public interface VertexProjector
    {
        /**
         * Writes the canvas x and y of the scene point into {@code out[0]} and {@code out[1]}.
         *
         * @return false when the point is off screen
         */
        boolean project(float x, float y, float z, int[] out);
    }

    private final VertexProjector projector;
    /** This frame's projection of each mesh drawn; cleared by {@link #startFrame}. */
    private final Map<TriangleMesh, Projection> projections = new IdentityHashMap<>();
    /** Projections reused across frames, so their buffers are allocated once. */
    private final List<Projection> pool = new ArrayList<>();
    private int poolUsed;
    private final int[] point = new int[2];

    public MeshProjector(VertexProjector projector)
    {
        this.projector = projector;
    }

    /** A new frame: the camera may have moved, so every vertex is projected again on first use. */
    public void startFrame()
    {
        projections.clear();
        poolUsed = 0;
    }

    /**
     * Puts triangle {@code t} of {@code mesh} into {@code out} as three canvas points.
     *
     * @return false (and {@code out} unusable) when a corner is off screen
     */
    public boolean triangle(TriangleMesh mesh, int triangle, Polygon out)
    {
        Projection projection = projection(mesh);
        out.reset();
        for (int corner = 0; corner < 3; corner++)
        {
            int vertex = mesh.faces[triangle * 3 + corner];
            if (!projection.project(vertex))
            {
                return false;
            }
            out.addPoint(projection.xs[vertex], projection.ys[vertex]);
        }
        return true;
    }

    private Projection projection(TriangleMesh mesh)
    {
        Projection projection = projections.get(mesh);
        if (projection == null)
        {
            if (poolUsed == pool.size())
            {
                pool.add(new Projection());
            }
            projection = pool.get(poolUsed++);
            projection.start(mesh);
            projections.put(mesh, projection);
        }
        return projection;
    }

    /** One mesh's projected vertices this frame. */
    private final class Projection
    {
        private static final byte UNKNOWN = 0;
        private static final byte ON_SCREEN = 1;
        private static final byte OFF_SCREEN = 2;

        private TriangleMesh mesh;
        private int[] xs = new int[0];
        private int[] ys = new int[0];
        private byte[] state = new byte[0];

        void start(TriangleMesh mesh)
        {
            this.mesh = mesh;
            int vertices = mesh.x.length;
            if (state.length < vertices)
            {
                xs = new int[vertices];
                ys = new int[vertices];
                state = new byte[vertices];
            }
            else
            {
                Arrays.fill(state, 0, vertices, UNKNOWN);
            }
        }

        boolean project(int vertex)
        {
            if (state[vertex] == UNKNOWN)
            {
                if (projector.project(mesh.x[vertex], mesh.y[vertex], mesh.z[vertex], point))
                {
                    xs[vertex] = point[0];
                    ys[vertex] = point[1];
                    state[vertex] = ON_SCREEN;
                }
                else
                {
                    state[vertex] = OFF_SCREEN;
                }
            }
            return state[vertex] == ON_SCREEN;
        }
    }
}
