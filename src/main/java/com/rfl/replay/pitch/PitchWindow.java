package com.rfl.replay.pitch;

import net.runelite.api.Constants;

/**
 * The scene tiles a pitch scans and crops to: {@code x0..x1} by {@code y0..y1}, inclusive, within
 * the 104x104 scene (empty when {@code x0 > x1}). Inside an instance (a house) it is the bounding
 * box of the instance's template chunks plus one tile, so a recorder standing off-centre still gets
 * every wall; elsewhere it is {@code radius} tiles (Chebyshev) around the recorder.
 */
public final class PitchWindow
{
    /** Tiles per side of the loaded scene. */
    static final int SCENE = Constants.SCENE_SIZE;
    /** No template chunk in that slot of {@code getInstanceTemplateChunks()}. */
    public static final int NO_CHUNK = -1;
    /** Tiles per side of a template chunk. */
    static final int CHUNK_TILES = Constants.CHUNK_SIZE;

    public final int x0;
    public final int y0;
    public final int x1;
    public final int y1;

    PitchWindow(int x0, int y0, int x1, int y1)
    {
        this.x0 = Math.max(0, x0);
        this.y0 = Math.max(0, y0);
        this.x1 = Math.min(SCENE - 1, x1);
        this.y1 = Math.min(SCENE - 1, y1);
    }

    /** {@code radius} tiles around scene tile {@code (cx, cy)}; empty for an off-scene centre. */
    public static PitchWindow around(int cx, int cy, int radius)
    {
        return new PitchWindow(cx - radius, cy - radius, cx + radius, cy + radius);
    }

    /**
     * The bounding box of every template chunk in {@code chunks} ({@code [plane][x][y]}, as from
     * {@code getInstanceTemplateChunks()}; null outside an instance) that isn't {@link #NO_CHUNK},
     * plus one tile, clamped to the scene; {@link #around} when there is none.
     */
    public static PitchWindow house(int[][][] chunks, int cx, int cy, int radius)
    {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        if (chunks != null)
        {
            for (int[][] plane : chunks)
            {
                for (int x = 0; plane != null && x < plane.length; x++)
                {
                    for (int y = 0; plane[x] != null && y < plane[x].length; y++)
                    {
                        if (plane[x][y] != NO_CHUNK)
                        {
                            minX = Math.min(minX, x);
                            minY = Math.min(minY, y);
                            maxX = Math.max(maxX, x);
                            maxY = Math.max(maxY, y);
                        }
                    }
                }
            }
        }
        if (maxX < 0)
        {
            return around(cx, cy, radius);
        }
        // One tile beyond the chunks on every side, so the house's outer walls are in.
        return new PitchWindow(minX * CHUNK_TILES - 1, minY * CHUNK_TILES - 1,
            (maxX + 1) * CHUNK_TILES, (maxY + 1) * CHUNK_TILES);
    }

    public boolean contains(int x, int y)
    {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1;
    }
}
