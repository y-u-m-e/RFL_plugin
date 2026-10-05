package com.rfl.contact;

import com.rfl.log.CollisionLog;

import java.util.List;

/**
 * One finished handegg collision between two players in view, saved on this computer only
 * ({@link CollisionLog}). Field names are the JSON keys of each saved line.
 */
public final class Collision
{
    /** Line type in the day file; incompletes are {@code "incomplete"}. */
    final String type = "collision";
    public final String a;
    public final String b;
    /** Names of the pair holding a handegg when the collision started: one, or both. */
    public final List<String> ball;
    public final long startMs;
    public final long endMs;
    final int startTick;
    final int endTick;
    final int world;
    /** World tile under the latest touching-triangle centroid; a template coordinate, see {@link #sx}. */
    final int x;
    final int y;
    final int plane;
    /**
     * Scene tile of the same point as {@link #x}/{@link #y}, unique within the loaded house; x/y are
     * template coordinates that repeat across a house (every instance template chunk reuses them).
     */
    public final int sx;
    public final int sy;
    /** Largest sampled touching triangle-pair count while the collision lasted. */
    public final int maxTriangles;

    public Collision(String a, String b, List<String> ball, long startMs, long endMs, int startTick, int endTick,
        int world, int x, int y, int plane, int sx, int sy, int maxTriangles)
    {
        this.a = a;
        this.b = b;
        this.ball = ball;
        this.startMs = startMs;
        this.endMs = endMs;
        this.startTick = startTick;
        this.endTick = endTick;
        this.world = world;
        this.x = x;
        this.y = y;
        this.plane = plane;
        this.sx = sx;
        this.sy = sy;
        this.maxTriangles = maxTriangles;
    }
}
