package com.playercollision;

import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

/**
 * Represents a single player-pair model overlap detected on one game tick.
 */
public final class CollisionEntry
{
    private final Player firstPlayer;
    private final Player secondPlayer;
    private final WorldPoint tile;

    /**
     * Creates an immutable collision entry for two overlapping player models.
     *
     * @param firstPlayer first player in the pair
     * @param secondPlayer second player in the pair
     * @param tile reference world tile near this overlap
     */
    public CollisionEntry(final Player firstPlayer, final Player secondPlayer, final WorldPoint tile)
    {
        this.firstPlayer = firstPlayer;
        this.secondPlayer = secondPlayer;
        this.tile = tile;
    }

    /**
     * Returns the first player involved in the collision.
     *
     * @return first colliding player
     */
    public Player getFirstPlayer()
    {
        return firstPlayer;
    }

    /**
     * Returns the second player involved in the collision.
     *
     * @return second colliding player
     */
    public Player getSecondPlayer()
    {
        return secondPlayer;
    }

    /**
     * Returns the tile shared by both players.
     *
     * @return world tile where collision occurred
     */
    public WorldPoint getTile()
    {
        return tile;
    }
}
