package com.rfl;

/**
 * A player's body as an upright cylinder in scene space: a horizontal circle (centre x/y, radius,
 * in local units, 128 = one tile) extruded from minZ to maxZ (up-positive). Unlike an axis-aligned
 * box, its footprint doesn't grow when the model turns, so players standing diagonally next to
 * each other don't register a contact (spec §3 "Contact detection", §9 upgrade path).
 */
final class Cylinder
{
    final int x;
    final int y;
    final int radius;
    final int minZ;
    final int maxZ;

    Cylinder(int x, int y, int radius, int minZ, int maxZ)
    {
        this.x = x;
        this.y = y;
        this.radius = radius;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    /**
     * @return 0 for no contact, else the penetration in local units: the smaller of the
     *     horizontal overlap (sum of radii minus centre distance) and the vertical overlap.
     *     Touching counts as no contact.
     */
    static int overlapDepth(Cylinder a, Cylinder b)
    {
        int vertical = Math.min(a.maxZ, b.maxZ) - Math.max(a.minZ, b.minZ);
        double horizontal = a.radius + b.radius - Math.hypot(b.x - a.x, b.y - a.y);
        if (vertical <= 0 || horizontal <= 0)
        {
            return 0;
        }
        return (int) Math.min(Math.round(horizontal), vertical);
    }

    /**
     * Scene-space point a contact happened at: the middle of the overlap along the line between
     * the two centres. Only meaningful when {@link #overlapDepth} is positive.
     *
     * @return {x, y} in local units
     */
    static int[] overlapCenter(Cylinder a, Cylinder b)
    {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double distance = Math.hypot(dx, dy);
        if (distance == 0)
        {
            return new int[]{a.x, a.y};
        }
        // Overlap runs from (distance - b.radius) to a.radius along the a->b line.
        double along = (a.radius + distance - b.radius) / 2;
        return new int[]{
            (int) Math.round(a.x + dx / distance * along),
            (int) Math.round(a.y + dy / distance * along),
        };
    }
}
