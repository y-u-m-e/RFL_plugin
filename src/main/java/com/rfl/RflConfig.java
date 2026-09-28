package com.rfl;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/**
 * User configuration for the RFL audit plugin.
 */
@ConfigGroup("rfl")
public interface RflConfig extends Config
{
    /**
     * Controls whether match reports are sent to the RFL audit server.
     *
     * @return true when reporting is enabled
     */
    @ConfigItem(
        keyName = "enableReporting",
        name = "Enable reporting",
        description = "While logged in, every 10 seconds, sends your RSN, world, enabled plugin list, and - "
            + "inside a player-owned house - the names of nearby players and contact events, to "
            + "api.ironforged.gg. Published publicly on rfl.gg. Off until you enable it.",
        warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by "
            + "RuneLite developers"
    )
    default boolean enableReporting()
    {
        return false;
    }

    /**
     * Reads the stored install identifier used to correlate reports from this client.
     *
     * @return stored install id, or empty when not yet assigned
     */
    @ConfigItem(
        keyName = "installId",
        name = "Install ID",
        description = "Internal install identifier used to correlate reports. Not user-facing.",
        hidden = true
    )
    default String installId()
    {
        return "";
    }

    /**
     * Stores the install identifier used to correlate reports from this client.
     *
     * @param installId install id to persist
     */
    @ConfigItem(
        keyName = "installId",
        name = "Install ID",
        description = "Internal install identifier used to correlate reports. Not user-facing.",
        hidden = true
    )
    void installId(String installId);
}
