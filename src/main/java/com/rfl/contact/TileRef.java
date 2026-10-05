package com.rfl.contact;

/**
 * Where a collision happened: the world tile under a point and the same point's scene tile. In a
 * house the world tile is a template coordinate (WorldPoint.fromLocalInstance maps every instance
 * chunk back to its template), so two players in the same house agree on it but it repeats across
 * the house; the scene tile (0-103) is unique within the loaded house.
 */
public final class TileRef
{
    /** Tests and placeholders: no tile. */
    public static final TileRef NONE = new TileRef(0, 0, 0, 0, 0);

    final int worldX;
    final int worldY;
    final int plane;
    final int sceneX;
    final int sceneY;

    public TileRef(int worldX, int worldY, int plane, int sceneX, int sceneY)
    {
        this.worldX = worldX;
        this.worldY = worldY;
        this.plane = plane;
        this.sceneX = sceneX;
        this.sceneY = sceneY;
    }
}
