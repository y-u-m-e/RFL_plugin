package com.rfl.replay;

import sh.yumekui.toolkit.model.ModelCapture;

/**
 * A captured player model plus where its held ball's faces sit in it, for the {@code model} line's
 * {@code held} field: {firstFace, faceCount} in the recorded {@code f} (hidden faces already
 * dropped), or null when the player holds no ball or its faces were not matched.
 */
final class PlayerModel
{
    /** Faces with this transparency are dropped from a recording ({@link ModelCapture#capture}). */
    private static final int FULLY_TRANSPARENT = 255;
    /** Face colour 3 of a face the client does not draw; also dropped. */
    private static final int HIDDEN_FACE = -2;

    final ModelCapture.Geometry geometry;
    final int[] held;

    PlayerModel(ModelCapture.Geometry geometry, int[] held)
    {
        this.geometry = geometry;
        this.held = held;
    }

    /** Captures a player's model; null when there is none. Called only for a new model key. */
    interface Source
    {
        PlayerModel capture();
    }

    /**
     * The client model's held-ball face range {start, end} (end exclusive) as recorded face indices
     * {firstFace, faceCount}: the faces {@link ModelCapture#capture} drops (fully transparent, or
     * colour 3 of -2) are not counted, before the range or inside it. Null for an empty range or
     * one with no recorded face left.
     */
    static int[] recorded(int[] live, int faceCount, int[] colors3, byte[] transparencies)
    {
        if (live == null || live.length < 2 || live[1] <= live[0] || live[0] < 0 || live[1] > faceCount)
        {
            return null;
        }
        int first = 0;
        int count = 0;
        for (int f = 0; f < live[1]; f++)
        {
            if (hidden(f, colors3, transparencies))
            {
                continue;
            }
            if (f < live[0])
            {
                first++;
            }
            else
            {
                count++;
            }
        }
        return count == 0 ? null : new int[] {first, count};
    }

    /** Same rule as {@link ModelCapture}'s own, so the indices line up with the recorded faces. */
    private static boolean hidden(int face, int[] colors3, byte[] transparencies)
    {
        int alpha = transparencies != null ? transparencies[face] & 0xff : 0;
        return alpha == FULLY_TRANSPARENT || colors3 != null && colors3[face] == HIDDEN_FACE;
    }
}
