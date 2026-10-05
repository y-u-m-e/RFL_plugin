package com.rfl.replay.pitch;

import net.runelite.api.Constants;

/**
 * Crops one plane of the scene floor arrays ({@code under}, {@code over}, {@code shapes}) to a
 * 104x104 {@code [x][y]} int grid holding values only within a {@link PitchWindow}, 0 elsewhere,
 * and lays out the {@code paint} array. Source planes may be scene-sized (104, or 105 with an edge
 * row) or extended-scene-sized (184+), in which case scene tile 0 sits at index 40.
 */
public final class PitchFloor
{
    static final int SCENE = PitchWindow.SCENE;
    /** The client's extended scene, which keeps 40 extra tiles on every side of the scene. */
    static final int EXTENDED_SCENE = Constants.EXTENDED_SCENE_SIZE;
    /** Red, green and blue per tile in {@code paint}. */
    private static final int CHANNELS = 3;
    /** Unsigned masks for the source arrays' element types. */
    private static final int UNSIGNED_SHORT = 0xFFFF;
    private static final int UNSIGNED_BYTE = 0xFF;
    /** Where red and green sit in a 0xRRGGBB colour; blue is the low byte. */
    private static final int RED_SHIFT = 16;
    private static final int GREEN_SHIFT = 8;

    private PitchFloor()
    {
    }

    /** Index of scene tile 0 in a source plane of {@code length} rows. */
    public static int offset(int length)
    {
        return length >= EXTENDED_SCENE ? (EXTENDED_SCENE - SCENE) / 2 : 0;
    }

    /** Unsigned crop of a {@code short} plane (underlay / overlay ids, stored as id + 1). */
    public static int[][] crop(short[][] plane, int cx, int cy, int radius)
    {
        return crop(plane, PitchWindow.around(cx, cy, radius));
    }

    /** Unsigned crop of a {@code short} plane to {@code window}. */
    public static int[][] crop(short[][] plane, PitchWindow window)
    {
        int[][] out = new int[SCENE][SCENE];
        if (plane == null)
        {
            return out;
        }
        int offset = offset(plane.length);
        for (int x = window.x0; x <= window.x1; x++)
        {
            short[] column = x + offset < plane.length ? plane[x + offset] : null;
            if (column == null)
            {
                continue;
            }
            for (int y = window.y0; y <= window.y1; y++)
            {
                if (y + offset < column.length)
                {
                    out[x][y] = column[y + offset] & UNSIGNED_SHORT;
                }
            }
        }
        return out;
    }

    /** Unsigned crop of a {@code byte} plane (tile shapes). */
    public static int[][] crop(byte[][] plane, int cx, int cy, int radius)
    {
        return crop(plane, PitchWindow.around(cx, cy, radius));
    }

    /** Unsigned crop of a {@code byte} plane to {@code window}. */
    public static int[][] crop(byte[][] plane, PitchWindow window)
    {
        int[][] out = new int[SCENE][SCENE];
        if (plane == null)
        {
            return out;
        }
        int offset = offset(plane.length);
        for (int x = window.x0; x <= window.x1; x++)
        {
            byte[] column = x + offset < plane.length ? plane[x + offset] : null;
            if (column == null)
            {
                continue;
            }
            for (int y = window.y0; y <= window.y1; y++)
            {
                if (y + offset < column.length)
                {
                    out[x][y] = column[y + offset] & UNSIGNED_BYTE;
                }
            }
        }
        return out;
    }

    /**
     * The {@code pitch.paint} array: an r, g, b triple per scene tile, tile {@code (x, y)} at
     * {@code (x * 104 + y) * 3}, from a 104x104 {@code [x][y]} grid of {@code 0xRRGGBB}. Tiles outside
     * the radius are cropped to 0, 0, 0 like {@code under}/{@code over}; 0, 0, 0 also means no paint.
     */
    public static int[] paint(int[][] rgb, int cx, int cy, int radius)
    {
        return paint(rgb, PitchWindow.around(cx, cy, radius));
    }

    /** {@link #paint(int[][], int, int, int)} cropped to {@code window}. */
    public static int[] paint(int[][] rgb, PitchWindow window)
    {
        int[] out = new int[SCENE * SCENE * CHANNELS];
        if (rgb == null)
        {
            return out;
        }
        for (int x = window.x0; x <= Math.min(window.x1, rgb.length - 1); x++)
        {
            int[] column = rgb[x];
            if (column == null)
            {
                continue;
            }
            for (int y = window.y0; y <= Math.min(window.y1, column.length - 1); y++)
            {
                int at = (x * SCENE + y) * CHANNELS;
                out[at] = (column[y] >> RED_SHIFT) & UNSIGNED_BYTE;
                out[at + 1] = (column[y] >> GREEN_SHIFT) & UNSIGNED_BYTE;
                out[at + 2] = column[y] & UNSIGNED_BYTE;
            }
        }
        return out;
    }
}
