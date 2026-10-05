package sh.yumekui.toolkit.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Turns a client model's arrays into compact geometry for saving, and keeps one id per model key.
 * Pure: it never touches the client. The caller passes the {@code net.runelite.api.Model}
 * getters ({@code getVerticesX/Y/Z}, {@code getVerticesCount}, {@code getFaceIndices1/2/3},
 * {@code getFaceCount}, {@code getFaceColors1/3}, {@code getFaceTransparencies},
 * {@code getFaceTextures}). Hidden faces
 * (transparency 255, or colour3 == -2) are dropped, textured faces are neutral grey, and every
 * other face is {@code faceColors1} through {@link Palette}. Vertices stay in raw client
 * orientation (the viewer rotates), rounded to ints.
 */
public final class ModelCapture
{
    /** Neutral grey for textured faces, matching the pack tool (no textures drawn). */
    static final int TEXTURED_GREY = 128;
    /** A face with this transparency (0-255, unsigned) is not drawn. */
    private static final int FULLY_TRANSPARENT = 255;
    /** {@code faceColors3} of a face the client does not draw. */
    private static final int HIDDEN_FACE = -2;
    /** Where red and green sit in a 0xRRGGBB colour, and one channel's mask. */
    private static final int RED_SHIFT = 16;
    private static final int GREEN_SHIFT = 8;
    private static final int BYTE = 0xff;
    /** {@code Perspective.SINE}/{@code COSINE} are 16.16 fixed point. */
    private static final int FIXED_POINT_SHIFT = 16;

    private ModelCapture()
    {
    }

    /** Captured geometry. The arrays are owned copies; treat them as immutable. */
    public static final class Geometry
    {
        /** x, y, z per vertex, model units, raw client orientation. */
        public final int[] vertices;
        /** Three vertex indices per kept face. */
        public final int[] faces;
        /** r, g, b (0..255) per kept face. */
        public final int[] colors;

        public Geometry(int[] vertices, int[] faces, int[] colors)
        {
            this.vertices = vertices;
            this.faces = faces;
            this.colors = colors;
        }
    }

    /** A geometry captured under a new key, with the id it was given. */
    public static final class Captured
    {
        public final int id;
        public final Geometry geometry;

        Captured(int id, Geometry geometry)
        {
            this.id = id;
            this.geometry = geometry;
        }
    }

    /**
     * Copies the model's arrays into a new {@link Geometry}. {@code transparencies} and
     * {@code textures} may be null (the client leaves them null on models without them).
     * Only the first {@code vCount} vertices and {@code fCount} faces are read.
     */
    public static Geometry capture(float[] vx, float[] vy, float[] vz, int vCount,
        int[] f1, int[] f2, int[] f3, int fCount,
        int[] colors1, int[] colors3, byte[] transparencies, short[] textures)
    {
        int[] vertices = new int[vCount * 3];
        for (int v = 0; v < vCount; v++)
        {
            vertices[v * 3] = Math.round(vx[v]);
            vertices[v * 3 + 1] = Math.round(vy[v]);
            vertices[v * 3 + 2] = Math.round(vz[v]);
        }

        int kept = 0;
        for (int f = 0; f < fCount; f++)
        {
            if (!hidden(f, colors3, transparencies))
            {
                kept++;
            }
        }

        int[] faces = new int[kept * 3];
        int[] colors = new int[kept * 3];
        int out = 0;
        for (int f = 0; f < fCount; f++)
        {
            if (hidden(f, colors3, transparencies))
            {
                continue;
            }
            faces[out] = f1[f];
            faces[out + 1] = f2[f];
            faces[out + 2] = f3[f];
            if (textures != null && textures[f] != -1)
            {
                colors[out] = TEXTURED_GREY;
                colors[out + 1] = TEXTURED_GREY;
                colors[out + 2] = TEXTURED_GREY;
            }
            else
            {
                int rgb = Palette.rgb(colors1[f]);
                colors[out] = (rgb >> RED_SHIFT) & BYTE;
                colors[out + 1] = (rgb >> GREEN_SHIFT) & BYTE;
                colors[out + 2] = rgb & BYTE;
            }
            out += 3;
        }
        return new Geometry(vertices, faces, colors);
    }

    /**
     * {@code geometry} turned about the vertical axis the way the GPU plugin places a static
     * GameObject with a non-zero model orientation ({@code sin}/{@code cos} from
     * {@code Perspective.SINE}/{@code COSINE}, 16.16 fixed point): {@code x' = (z*sin + x*cos) >> 16},
     * {@code z' = (z*cos - x*sin) >> 16}, y unchanged. Faces and colours are shared, not copied.
     */
    public static Geometry rotateY(Geometry geometry, int sin, int cos)
    {
        final int[] v = geometry.vertices;
        final int[] out = new int[v.length];
        for (int k = 0; k + 2 < v.length; k += 3)
        {
            final int x = v[k];
            final int z = v[k + 2];
            out[k] = (z * sin + x * cos) >> FIXED_POINT_SHIFT;
            out[k + 1] = v[k + 1];
            out[k + 2] = (z * cos - x * sin) >> FIXED_POINT_SHIFT;
        }
        return new Geometry(out, geometry.faces, geometry.colors);
    }

    private static boolean hidden(int face, int[] colors3, byte[] transparencies)
    {
        int alpha = transparencies != null ? transparencies[face] & BYTE : 0;
        return alpha == FULLY_TRANSPARENT || colors3[face] == HIDDEN_FACE;
    }

    /**
     * Assigns ids 0, 1, 2, ... to model keys, capturing each key's geometry only the first time
     * it is seen. Later models for the same key (for example animation-smoothing tweens) are
     * ignored, so a recording never grows with variants.
     *
     * <p>Not thread-safe, by design: use it from the client thread only. {@link #takeNew()} hands
     * newly captured geometry over for serialisation on the writer's thread; the registry never
     * touches a returned {@link Geometry} again.
     */
    public static final class Registry
    {
        private final Map<String, Integer> ids = new HashMap<>();
        private List<Captured> fresh = new ArrayList<>();

        /**
         * The id for {@code key}. On a new key, calls {@code capture} once and queues its
         * geometry for {@link #takeNew()}; on a known key, {@code capture} is not called. When
         * {@code capture} yields null (no model this time), returns -1 and registers nothing, so a
         * later call can still capture the key.
         */
        public int idFor(String key, Supplier<Geometry> capture)
        {
            Integer known = ids.get(key);
            if (known != null)
            {
                return known;
            }
            Geometry geometry = capture.get();
            if (geometry == null)
            {
                return -1;
            }
            int id = ids.size();
            ids.put(key, id);
            fresh.add(new Captured(id, geometry));
            return id;
        }

        /** Newly captured geometries since the last call, in id order. Each is returned once. */
        public List<Captured> takeNew()
        {
            if (fresh.isEmpty())
            {
                return new ArrayList<>();
            }
            List<Captured> out = fresh;
            fresh = new ArrayList<>();
            return out;
        }

        /** Distinct keys seen so far (models captured). */
        public int size()
        {
            return ids.size();
        }
    }
}
