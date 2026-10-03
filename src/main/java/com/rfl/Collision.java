package com.rfl;

import java.util.List;

/**
 * One finished handegg collision between two players in view, saved on this computer only
 * ({@link CollisionLog}). Field names are the JSON keys of each saved line.
 */
final class Collision
{
    /** Line type in the day file; interceptions are {@code "interception"}. */
    final String type = "collision";
    final String a;
    final String b;
    /** Names of the pair holding a handegg when the collision started: one, or both. */
    final List<String> ball;
    final long startMs;
    final long endMs;
    final int startTick;
    final int endTick;
    final int world;
    /** World tile under the latest touching-triangle centroid. */
    final int x;
    final int y;
    final int plane;
    /** Largest sampled touching triangle-pair count while the collision lasted. */
    final int maxTriangles;

    Collision(String a, String b, List<String> ball, long startMs, long endMs, int startTick, int endTick,
        int world, int x, int y, int plane, int maxTriangles)
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
        this.maxTriangles = maxTriangles;
    }
}
