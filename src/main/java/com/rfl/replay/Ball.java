package com.rfl.replay;

import sh.yumekui.toolkit.model.ModelCapture;

import java.util.function.Supplier;

/** One thrown handegg projectile this client cycle. */
final class Ball
{
    /** The projectile's spot anim id. */
    final int id;
    /** The client cycle the projectile starts moving; before it, the client parks it at (0, 0). */
    final int startCycle;
    final double x;
    final double y;
    final double z;
    final int orient;
    /** Captures the projectile's client model, or null; called only for a new key. May be null. */
    final Supplier<ModelCapture.Geometry> model;

    Ball(int id, int startCycle, double x, double y, double z, int orient, Supplier<ModelCapture.Geometry> model)
    {
        this.id = id;
        this.startCycle = startCycle;
        this.x = x;
        this.y = y;
        this.z = z;
        this.orient = orient;
        this.model = model;
    }
}
