package sh.yumekui.toolkit.overlay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import sh.yumekui.toolkit.geom.TriangleMesh;
import java.awt.Polygon;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * {@link MeshProjector}: a vertex shared by several triangles is projected once per frame, again on
 * the next frame, and a triangle with a corner off screen comes back unusable.
 */
public class MeshProjectorTest
{
    /** Two triangles sharing the edge 1-2: four vertices, six corners. */
    private static TriangleMesh square()
    {
        return new TriangleMesh(new float[] { 0, 10, 0, 10 }, new float[] { 0, 0, 10, 10 }, new float[4],
            new int[] { 0, 1, 2, 1, 3, 2 });
    }

    @Test
    public void sharedVerticesAreProjectedOncePerFrame()
    {
        AtomicInteger projections = new AtomicInteger();
        // A projector that doubles x and y, counting its calls.
        MeshProjector projector = new MeshProjector((x, y, z, out) ->
        {
            projections.incrementAndGet();
            out[0] = (int) (x * 2);
            out[1] = (int) (y * 2);
            return true;
        });
        TriangleMesh mesh = square();
        Polygon triangle = new Polygon();

        projector.startFrame();
        assertTrue(projector.triangle(mesh, 0, triangle));
        assertArrayEquals(new int[] { 0, 20, 0 }, java.util.Arrays.copyOf(triangle.xpoints, triangle.npoints));
        assertTrue(projector.triangle(mesh, 1, triangle));
        assertEquals("four vertices, not six corners", 4, projections.get());

        projector.startFrame();
        projector.triangle(mesh, 0, triangle);
        assertEquals("a new frame projects again", 7, projections.get());
    }

    @Test
    public void aTriangleWithACornerOffScreenIsSkipped()
    {
        // Vertex 3 (x 10, y 10) is off screen.
        MeshProjector projector = new MeshProjector((x, y, z, out) -> !(x == 10 && y == 10));
        TriangleMesh mesh = square();
        projector.startFrame();
        assertTrue(projector.triangle(mesh, 0, new Polygon()));
        assertFalse(projector.triangle(mesh, 1, new Polygon()));
    }
}
