package com.rfl.replay;

import sh.yumekui.toolkit.model.ModelCapture;

import java.util.function.Supplier;

/** One player's pose this client cycle, as the recorder read it for {@link ReplaySampler#frame}. */
public final class PlayerState
{
    /** Shared empty spot anim set, so a player with none costs no allocation. */
    public static final int[] NO_SPOTS = new int[0];

    final String name;
    /** Local position, 128 units per tile. */
    final int x;
    final int y;
    /** Orientation, 0-2047 for a full turn. */
    final int orient;
    final int anim;
    final int animFrame;
    final int pose;
    final int poseFrame;

    /**
     * Spot anims as flat {@code (id, frame, height)} triples in any order; {@link #NO_SPOTS} when
     * there are none. The sampler sorts the triples in place.
     */
    final int[] spots;

    /**
     * Captures this player's current client model ({@code Player#getModel()}), or null when there is
     * none. Called on the client thread, only when the player's model key is new. May be null itself
     * (no model capture, for example in tests of other line types).
     */
    final Supplier<ModelCapture.Geometry> model;

    /**
     * {@link Appearance#hash(int, int[], int[])} of the player's composition read this frame, or null
     * when unknown. When it differs from the last {@link ReplaySampler#tick} hash, the look changed
     * since that tick, so the sampler waits for the next tick before capturing.
     */
    final Integer look;

    public PlayerState(String name, int x, int y, int orient, int anim, int animFrame, int pose, int poseFrame,
        int[] spots, Supplier<ModelCapture.Geometry> model, Integer look)
    {
        this.name = name;
        this.x = x;
        this.y = y;
        this.orient = orient;
        this.anim = anim;
        this.animFrame = animFrame;
        this.pose = pose;
        this.poseFrame = poseFrame;
        this.spots = spots == null ? NO_SPOTS : spots;
        this.model = model;
        this.look = look;
    }

    /** The {@code f} row for this pose under index {@code i}. */
    int[] row(int index)
    {
        return new int[] { index, x, y, orient, anim, animFrame, pose, poseFrame };
    }
}
