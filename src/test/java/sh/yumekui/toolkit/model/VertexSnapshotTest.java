package sh.yumekui.toolkit.model;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/**
 * {@link VertexSnapshot}, the guard that keeps a cached base model unchanged however it is posed. Each
 * test poses arrays in place, the worst case the client might do, and checks that restoring the
 * snapshot first makes every pose start from the untouched base.
 */
public class VertexSnapshotTest
{
    /** Moves every vertex 5 units along x and hides face 0, in place, like an animation frame might. */
    private static void poseInPlace(float[] x, byte[] transparencies)
    {
        for (int v = 0; v < x.length; v++)
        {
            x[v] += 5;
        }
        transparencies[0] = (byte) 255;
    }

    @Test
    public void theCachedBaseIsUnchangedAfterPosingOnceRestored()
    {
        float[] x = { 0, 10, 20 };
        float[] y = { 1, 2, 3 };
        float[] z = { -4, -5, -6 };
        byte[] transparencies = { 0, 100 };
        VertexSnapshot pristine = VertexSnapshot.of(x, y, z, x.length, transparencies);

        poseInPlace(x, transparencies);
        pristine.restoreInto(x, y, z, transparencies);

        assertArrayEquals(new float[] { 0, 10, 20 }, x, 0f);
        assertArrayEquals(new float[] { 1, 2, 3 }, y, 0f);
        assertArrayEquals(new float[] { -4, -5, -6 }, z, 0f);
        assertArrayEquals(new byte[] { 0, 100 }, transparencies);
    }

    @Test
    public void repeatedPosesNeverDriftWhenEachStartsFromTheSnapshot()
    {
        float[] x = { 0, 10, 20 };
        float[] y = { 0, 0, 0 };
        float[] z = { 0, 0, 0 };
        byte[] transparencies = { 0, 0 };
        VertexSnapshot pristine = VertexSnapshot.of(x, y, z, x.length, transparencies);

        for (int frame = 0; frame < 3; frame++)
        {
            pristine.restoreInto(x, y, z, transparencies);
            poseInPlace(x, transparencies);
            // Without the restore, frame 3 would be 15 units off instead of 5.
            assertArrayEquals("frame " + frame, new float[] { 5, 15, 25 }, x, 0f);
        }
    }

    @Test
    public void onlyTheLiveVerticesAreKeptAndMissingTransparenciesAreFine()
    {
        // Client arrays can be longer than the live vertex count; the tail is never touched.
        float[] x = { 1, 2, 99 };
        float[] y = { 1, 2, 99 };
        float[] z = { 1, 2, 99 };
        VertexSnapshot pristine = VertexSnapshot.of(x, y, z, 2, null);
        x[0] = 7;
        x[2] = 42;

        pristine.restoreInto(x, y, z, null);

        assertArrayEquals(new float[] { 1, 2, 42 }, x, 0f);
    }
}
