package com.rfl.replay.pitch;

import sh.yumekui.toolkit.model.ModelCapture;

/** What reading one loc's model yielded: geometry, or the reason there is none. */
public final class LocModel
{
    final ModelCapture.Geometry geometry;
    final LocSkip skip;

    private LocModel(ModelCapture.Geometry geometry, LocSkip skip)
    {
        this.geometry = geometry;
        this.skip = skip;
    }

    /** The geometry, or {@link LocSkip#NO_MODEL} when it is null. */
    public static LocModel of(ModelCapture.Geometry geometry)
    {
        return geometry == null ? skipped(LocSkip.NO_MODEL) : new LocModel(geometry, null);
    }

    public static LocModel skipped(LocSkip skip)
    {
        return new LocModel(null, skip);
    }
}
