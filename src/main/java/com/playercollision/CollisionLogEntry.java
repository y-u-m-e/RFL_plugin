package com.playercollision;

import net.runelite.api.coords.WorldPoint;

/**
 * Represents one history row shown in the plugin side panel.
 */
public final class CollisionLogEntry
{
    private final String timestamp;
    private final String firstPlayerName;
    private final String secondPlayerName;
    private final WorldPoint tile;

    /**
     * Creates a collision history entry.
     *
     * @param timestamp display-ready timestamp for when the collision was detected
     * @param firstPlayerName first colliding player's name
     * @param secondPlayerName second colliding player's name
     * @param tile world tile where collision occurred
     */
    public CollisionLogEntry(
        final String timestamp,
        final String firstPlayerName,
        final String secondPlayerName,
        final WorldPoint tile
    )
    {
        this.timestamp = timestamp;
        this.firstPlayerName = firstPlayerName;
        this.secondPlayerName = secondPlayerName;
        this.tile = tile;
    }

    /**
     * Returns the timestamp string used in the panel.
     *
     * @return formatted timestamp
     */
    public String getTimestamp()
    {
        return timestamp;
    }

    /**
     * Returns the first player's name.
     *
     * @return first player name
     */
    public String getFirstPlayerName()
    {
        return firstPlayerName;
    }

    /**
     * Returns the second player's name.
     *
     * @return second player name
     */
    public String getSecondPlayerName()
    {
        return secondPlayerName;
    }

    /**
     * Returns the collision world tile.
     *
     * @return collision tile
     */
    public WorldPoint getTile()
    {
        return tile;
    }
}
