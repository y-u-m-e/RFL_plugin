package com.rfl.replay.pitch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.runelite.api.Perspective;

/**
 * Collects the {@code pitch} line's {@code objs} rows {@code [id, type, orient, x, y]} and the
 * matching {@code objs2} rows {@code [id, kind, config, x, y, sizeX, sizeY]}, listing each object
 * once. A GameObject spanning several tiles is offered once per tile with the same hash;
 * {@link #add} keeps the first.
 *
 * <p>{@code config} is the object's raw scene config: {@link #shape} is {@code config & 31} (roof
 * shapes 12..21 included), {@link #rotation} is {@code (config >>> 6) & 3}.
 */
public final class PitchObjects
{
    /** Row {@code type} values, one per scene object layer. */
    public static final int GAME = 0;
    public static final int WALL = 1;
    static final int GROUND = 2;
    static final int DECORATIVE = 3;
    private static final int LAYERS = 4;

    /** The shape's bits of a scene config. */
    private static final int SHAPE_MASK = 31;
    /** Where the rotation sits in a scene config, and its two bits. */
    private static final int ROTATION_SHIFT = 6;
    private static final int ROTATION_MASK = 3;
    /** Local units per scene tile, and half of it to reach a tile's centre. */
    private static final int TILE_SIZE = Perspective.LOCAL_TILE_SIZE;
    private static final int HALF_TILE = TILE_SIZE / 2;

    /** Hashes seen so far, one set per layer: a hash is only unique within its layer. */
    private final List<Set<Long>> seen = new ArrayList<>(LAYERS);
    private final List<int[]> rows = new ArrayList<>();
    private final List<int[]> rows2 = new ArrayList<>();

    public PitchObjects()
    {
        for (int layer = 0; layer < LAYERS; layer++)
        {
            seen.add(new HashSet<>());
        }
    }

    /** Object shape from a scene config: 0..3 walls, 4..8 wall decorations, 10/11 game, 12..21 roofs, 22 ground. */
    public static int shape(int config)
    {
        return config & SHAPE_MASK;
    }

    /** Object rotation (0..3, quarter turns) from a scene config. */
    public static int rotation(int config)
    {
        return (config >>> ROTATION_SHIFT) & ROTATION_MASK;
    }

    /** Local coordinate of the centre of scene tile {@code index}. */
    public static int tileCentre(int index)
    {
        return index * TILE_SIZE + HALF_TILE;
    }

    /** Tiles spanned from {@code min} to {@code max} inclusive. */
    public static int span(int min, int max)
    {
        return max - min + 1;
    }

    /**
     * Adds one object to both {@code objs} ({@code x}/{@code y}: its local location) and
     * {@code objs2} ({@code x2}/{@code y2}: for a GameObject the south-west tile centre, else the
     * local location). False (and nothing added) when this type and hash were already added.
     */
    public boolean add(int type, long hash, int id, int orient, int x, int y, int config, int x2, int y2,
        int sizeX, int sizeY)
    {
        if (!seen.get(type).add(hash))
        {
            return false;
        }
        rows.add(new int[] { id, type, orient, x, y });
        rows2.add(new int[] { id, type, config, x2, y2, sizeX, sizeY });
        return true;
    }

    public List<int[]> rows()
    {
        return rows;
    }

    public List<int[]> rows2()
    {
        return rows2;
    }
}
