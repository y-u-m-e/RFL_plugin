package com.rfl.replay.pitch;

/** Why a {@code pitch.locs} candidate got no row, counted for the close summary log. */
public enum LocSkip
{
    /** The object had no renderable. */
    NO_RENDERABLE("noRenderable"),
    /** The renderable resolved to no model. */
    NO_MODEL("noModel"),
    /** The model had no vertex, face or colour arrays. */
    NO_ARRAYS("noArrays"),
    /** Reading the model threw. */
    THREW("threw"),
    /** An earlier object with the same key failed this pitch, so it was not read again. */
    SAME_KEY_FAILED("sameKeyFailed"),
    /** The recording stopped before the spread-out capture reached it. */
    UNFINISHED("unfinished");

    /** The name in the close summary log. */
    public final String label;

    LocSkip(String label)
    {
        this.label = label;
    }
}
