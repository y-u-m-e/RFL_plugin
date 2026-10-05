package com.rfl.replay.pitch;

import java.util.function.Supplier;

/**
 * One house object renderable at pitch time, for {@code pitch.locs}. {@code part} is 0 for an
 * object's (first) renderable and 1 for a wall's or decoration's second one, which is a different
 * model under the same loc id, shape and rotation. {@code orient} is a GameObject's model
 * orientation, baked into the captured vertices (0 for everything else); a non-zero one is part of
 * the key.
 */
public final class Loc
{
    /** Row {@code kind} values: GroundObject, DecorativeObject, WallObject, GameObject. */
    public static final char GROUND = 'g';
    public static final char DECORATIVE = 'd';
    public static final char WALL = 'w';
    public static final char GAME = 'o';

    final int id;
    /** {@link #GROUND}, {@link #DECORATIVE}, {@link #WALL} or {@link #GAME}. */
    final char kind;
    final int config;
    final int part;
    final int orient;
    final int x;
    final int y;
    final int height;
    /** Reads the renderable's model; called only for a new key. Null when there is no renderable. */
    final Supplier<LocModel> capture;

    private Loc(int id, char kind, int config, int part, int orient, int x, int y, int height,
        Supplier<LocModel> capture)
    {
        this.id = id;
        this.kind = kind;
        this.config = config;
        this.part = part;
        this.orient = orient;
        this.x = x;
        this.y = y;
        this.height = height;
        this.capture = capture;
    }

    /** A GameObject loc whose capture reports why it has no model. A null supplier: no renderable. */
    public static Loc withReasons(int id, int config, int part, int orient, int x, int y, int height,
        Supplier<LocModel> capture)
    {
        return new Loc(id, GAME, config, part, orient, x, y, height, capture);
    }

    /** {@link #withReasons} for an object of {@code kind} ({@link #GROUND}, ...). */
    public static Loc withReasons(int id, char kind, int config, int part, int orient, int x, int y, int height,
        Supplier<LocModel> capture)
    {
        return new Loc(id, kind, config, part, orient, x, y, height, capture);
    }

    /** The model key: {@code l:<id>:<shape>:<rotation>}, then {@code :<part>} and {@code :o<orient>} when non-zero. */
    public String key()
    {
        String key = "l:" + id + ":" + PitchObjects.shape(config) + ":" + PitchObjects.rotation(config);
        if (part != 0)
        {
            key += ":" + part;
        }
        return orient == 0 ? key : key + ":o" + orient;
    }

    /** This loc's {@code pitch.locs} row: {@code [modelId, localX, localY, groundHeight, locId, kind]}. */
    Object[] row(int modelId)
    {
        return new Object[] { modelId, x, y, height, id, String.valueOf(kind) };
    }
}
