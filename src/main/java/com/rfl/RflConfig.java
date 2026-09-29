package com.rfl;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * User configuration for the RFL audit plugin.
 */
@ConfigGroup("rfl")
public interface RflConfig extends Config
{
    @ConfigSection(
        name = "Match",
        description = "The match you're playing. Sent with every report and shown publicly on rfl.gg.",
        position = 1
    )
    String MATCH_SECTION = "match";

    @ConfigSection(
        name = "Features",
        description = "Each feature can be turned off. Any feature that is off is reported and shown "
            + "publicly on rfl.gg as a flag.",
        position = 4
    )
    String FEATURES_SECTION = "features";

    /**
     * Controls whether match reports are sent to the RFL audit server.
     *
     * @return true when reporting is enabled
     */
    @ConfigItem(
        keyName = "enableReporting",
        name = "Enable reporting",
        position = 0,
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
     * @return the league's code for this game, as typed (normalized when sent)
     */
    @ConfigItem(
        keyName = "matchCode",
        name = "Match code",
        description = "The code your league gave this game, e.g. W3-G2. Shown on rfl.gg; a code that "
            + "differs from the rest of your match is flagged.",
        section = MATCH_SECTION,
        position = 2
    )
    default String matchCode()
    {
        return "";
    }

    /**
     * @return the player's team name for this match, as typed (normalized when sent)
     */
    @ConfigItem(
        keyName = "team",
        name = "Team",
        description = "Your team name for this match. Shown on rfl.gg.",
        section = MATCH_SECTION,
        position = 3
    )
    default String team()
    {
        return "";
    }

    /**
     * @return true to send the enabled plugin list and plugin toggle events
     */
    @ConfigItem(
        keyName = "reportPlugins",
        name = "Report plugin list",
        description = "Sends your enabled plugin list and plugin on/off changes. Turning this off is "
            + "reported and shown publicly on rfl.gg as a flag.",
        section = FEATURES_SECTION,
        position = 5
    )
    default boolean reportPlugins()
    {
        return true;
    }

    /**
     * @return true to run contact detection inside a player-owned house
     */
    @ConfigItem(
        keyName = "reportContacts",
        name = "Detect contacts",
        description = "Detects and sends contact events between players inside a player-owned house. "
            + "Turning this off is reported and shown publicly on rfl.gg as a flag.",
        section = FEATURES_SECTION,
        position = 6
    )
    default boolean reportContacts()
    {
        return true;
    }

    /**
     * @return true to send the names of nearby players inside a player-owned house
     */
    @ConfigItem(
        keyName = "reportNearby",
        name = "Report nearby players",
        description = "Sends the names of nearby players inside a player-owned house. Turning this off "
            + "is reported and shown publicly on rfl.gg as a flag.",
        section = FEATURES_SECTION,
        position = 7
    )
    default boolean reportNearby()
    {
        return true;
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
