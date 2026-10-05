package sh.yumekui.toolkit.model;

/**
 * A copy of a model's vertex positions and face transparencies, taken before the model is ever
 * posed, that can be written back over the live arrays. Posing with an animation may move the
 * vertices of the model it is given in place; restoring the snapshot first makes every pose start
 * from the untouched model, so a cached model never drifts from frame to frame.
 *
 * <p>Not thread-safe; use it on the thread that poses the model.
 */
public final class VertexSnapshot
{
    private final float[] x;
    private final float[] y;
    private final float[] z;
    /** Null when the model has no face transparencies. */
    private final byte[] transparencies;

    private VertexSnapshot(float[] x, float[] y, float[] z, byte[] transparencies)
    {
        this.x = x;
        this.y = y;
        this.z = z;
        this.transparencies = transparencies;
    }

    /**
     * Copies the first {@code vertexCount} vertices and the face transparencies (null allowed).
     */
    public static VertexSnapshot of(float[] x, float[] y, float[] z, int vertexCount, byte[] transparencies)
    {
        return new VertexSnapshot(copy(x, vertexCount), copy(y, vertexCount), copy(z, vertexCount),
            transparencies == null ? null : transparencies.clone());
    }

    /** Writes the snapshot back over the live arrays, which must be the ones it was taken from. */
    public void restoreInto(float[] liveX, float[] liveY, float[] liveZ, byte[] liveTransparencies)
    {
        System.arraycopy(x, 0, liveX, 0, x.length);
        System.arraycopy(y, 0, liveY, 0, y.length);
        System.arraycopy(z, 0, liveZ, 0, z.length);
        if (transparencies != null && liveTransparencies != null)
        {
            System.arraycopy(transparencies, 0, liveTransparencies, 0, transparencies.length);
        }
    }

    private static float[] copy(float[] values, int count)
    {
        float[] out = new float[count];
        System.arraycopy(values, 0, out, 0, count);
        return out;
    }
}
