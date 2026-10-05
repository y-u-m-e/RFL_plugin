package com.rfl.incomplete;

import com.rfl.log.CollisionLog;

import java.util.List;

/**
 * One incomplete as saved on this computer ({@link CollisionLog}) and in replays: a catch made while
 * in contact with an opposing player. Field names are the JSON keys of each saved line, in this
 * order; do not reorder or rename them.
 */
public final class Incomplete
{
    /** Line type in the day file; collisions are {@code "collision"}. */
    final String type = "incomplete";
    public final String receiver;
    /** Who the receiver was in contact with when they caught it. */
    public final List<String> contacts;
    public final long timeMs;
    public final int tick;
    final int world;
    /** World tile under the receiver; a template coordinate, see {@link #sx}. */
    final int x;
    final int y;
    final int plane;
    /**
     * Scene tile of the same point as {@link #x}/{@link #y}, unique within the loaded house; x/y are
     * template coordinates that repeat across a house (every instance template chunk reuses them).
     */
    public final int sx;
    public final int sy;
    /**
     * Client cycle of the catch (the handegg projectile disappearing), the clock replay lines use; -1
     * when unknown. {@link #timeMs} is the same moment and {@link #tick} the game tick it fell in,
     * kept for older readers.
     */
    public final int catchCyc;

    public Incomplete(String receiver, List<String> contacts, long timeMs, int tick, int world, int x, int y,
        int plane, int sx, int sy, int catchCyc)
    {
        this.catchCyc = catchCyc;
        this.receiver = receiver;
        this.contacts = contacts;
        this.timeMs = timeMs;
        this.tick = tick;
        this.world = world;
        this.x = x;
        this.y = y;
        this.plane = plane;
        this.sx = sx;
        this.sy = sy;
    }
}
