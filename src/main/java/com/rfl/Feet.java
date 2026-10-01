package com.rfl;

import java.util.ArrayList;
import java.util.List;

/**
 * Footprints of a posed player model: the vertices near the soles, split into left and right by
 * the model's centre line, each turned into a rectangle in scene space. Display only.
 */
final class Feet
{
    /** Vertices within this height of the lowest point count as part of a foot (local units). */
    static final int FOOT_HEIGHT = 20;

    private Feet()
    {
    }

    /**
     * @param xs model-space vertex X (horizontal)
     * @param ys model-space vertex Y (vertical, negative-up)
     * @param zs model-space vertex Z (horizontal)
     * @param count number of vertices to read
     * @param orientation actor orientation, 0-2047 for a full turn
     * @param baseX scene X of the player (LocalPoint)
     * @param baseY scene Y of the player (LocalPoint)
     * @return up to two footprints, left first; each is four scene {x, y} corners in order
     */
    static List<int[][]> footprints(float[] xs, float[] ys, float[] zs, int count, int orientation, int baseX, int baseY)
    {
        List<int[][]> feet = new ArrayList<>();
        if (count == 0)
        {
            return feet;
        }

        float bottom = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++)
        {
            bottom = Math.max(bottom, ys[i]);
        }

        float sumX = 0;
        int soleCount = 0;
        for (int i = 0; i < count; i++)
        {
            if (ys[i] >= bottom - FOOT_HEIGHT)
            {
                sumX += xs[i];
                soleCount++;
            }
        }
        float centre = sumX / soleCount;

        // [minX, maxX, minZ, maxZ] per side; left = below the centre line.
        float[][] bounds = {
            {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE},
            {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE},
        };
        boolean[] present = new boolean[2];
        for (int i = 0; i < count; i++)
        {
            if (ys[i] < bottom - FOOT_HEIGHT)
            {
                continue;
            }
            int side = xs[i] < centre ? 0 : 1;
            present[side] = true;
            float[] b = bounds[side];
            b[0] = Math.min(b[0], xs[i]);
            b[1] = Math.max(b[1], xs[i]);
            b[2] = Math.min(b[2], zs[i]);
            b[3] = Math.max(b[3], zs[i]);
        }

        double angle = orientation * 2 * Math.PI / 2048;
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        for (int side = 0; side < 2; side++)
        {
            if (!present[side])
            {
                continue;
            }
            float[] b = bounds[side];
            float[][] corners = {{b[0], b[2]}, {b[1], b[2]}, {b[1], b[3]}, {b[0], b[3]}};
            int[][] scene = new int[4][2];
            for (int c = 0; c < 4; c++)
            {
                double x = corners[c][0];
                double z = corners[c][1];
                // Same rotation the client applies to models: x' = x cos + z sin, z' = z cos - x sin.
                scene[c][0] = baseX + (int) Math.round(x * cos + z * sin);
                scene[c][1] = baseY + (int) Math.round(z * cos - x * sin);
            }
            feet.add(scene);
        }
        return feet;
    }
}
