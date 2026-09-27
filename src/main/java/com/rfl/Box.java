package com.rfl;

/**
 * Axis-aligned bounding box in world tile coordinates. Pure value type — no RuneLite
 * dependency; Task 4 fills instances from real model bounds.
 */
final class Box
{
    final int minX, maxX, minY, maxY, minZ, maxZ;

    Box(int minX, int maxX, int minY, int maxY, int minZ, int maxZ)
    {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    /**
     * Smallest per-axis overlap of {@code a} and {@code b}, or {@code 0} if any axis
     * doesn't overlap (touching edges count as no overlap).
     */
    static int overlapDepth(Box a, Box b)
    {
        int x = Math.min(a.maxX, b.maxX) - Math.max(a.minX, b.minX);
        int y = Math.min(a.maxY, b.maxY) - Math.max(a.minY, b.minY);
        int z = Math.min(a.maxZ, b.maxZ) - Math.max(a.minZ, b.minZ);

        if (x <= 0 || y <= 0 || z <= 0)
        {
            return 0;
        }

        return Math.min(x, Math.min(y, z));
    }
}
