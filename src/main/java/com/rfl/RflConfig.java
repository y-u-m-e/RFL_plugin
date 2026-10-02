package com.rfl;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

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

    @ConfigSection(
        name = "Detection",
        description = "How contacts are detected. For testing; this will be fixed to one mode for everyone later.",
        position = 7
    )
    String DETECTION_SECTION = "detection";

    @ConfigSection(
        name = "Display",
        description = "What the plugin draws on your screen. Display only; nothing here is reported.",
        position = 8
    )
    String DISPLAY_SECTION = "display";

    /**
     * @return true to briefly highlight the tile under each contact
     */
    @ConfigItem(
        keyName = "highlightContacts",
        name = "Highlight contacts",
        description = "Briefly highlights the tile under the point where two players' models touch. "
            + "Needs reporting and contact detection on, inside a player-owned house.",
        section = DISPLAY_SECTION,
        position = 9
    )
    default boolean highlightContacts()
    {
        return true;
    }

    /**
     * @return colour of the contact tile highlight, including transparency
     */
    @Alpha
    @ConfigItem(
        keyName = "contactHighlightColor",
        name = "Contact highlight colour",
        description = "Colour of the contact tile highlight.",
        section = DISPLAY_SECTION,
        position = 10
    )
    default Color contactHighlightColor()
    {
        return new Color(255, 230, 0, 153);
    }

    /**
     * @return how long a contact highlight takes to fade out, in milliseconds
     */
    @Range(min = 200, max = 5000)
    @Units(Units.MILLISECONDS)
    @ConfigItem(
        keyName = "highlightDurationMs",
        name = "Highlight duration",
        description = "How long a contact highlight takes to fade out.",
        section = DISPLAY_SECTION,
        position = 11
    )
    default int highlightDurationMs()
    {
        return 1200;
    }

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
     * @return true to draw the feet cylinders of players whose bodies overlap
     */
    @ConfigItem(
        keyName = "showOverlap",
        name = "Show overlap",
        description = "While two players' bodies overlap, draws the cylinder around each player's feet "
            + "and fills where they cross: faint for a graze, the contact colour once it counts as a contact.",
        section = DISPLAY_SECTION,
        position = 12
    )
    default boolean showOverlap()
    {
        return false;
    }

    /**
     * @return true to draw every player's body-part hitboxes
     */
    @ConfigItem(
        keyName = "showHitboxes",
        name = "Show hitboxes",
        description = "Draws the body parts (legs, torso, arms, head) contacts are detected from, for every "
            + "player in view while contact detection runs. Parts in a contact use the contact colour.",
        section = DISPLAY_SECTION,
        position = 13
    )
    default boolean showHitboxes()
    {
        return false;
    }

    /**
     * @return colour of the hitbox outlines, including transparency
     */
    @Alpha
    @ConfigItem(
        keyName = "hitboxColor",
        name = "Hitbox colour",
        description = "Colour of hitbox outlines not in a contact.",
        section = DISPLAY_SECTION,
        position = 14
    )
    default Color hitboxColor()
    {
        return new Color(255, 255, 255, 115);
    }

    /** Which model body-part hitboxes are built from. */
    enum HitboxSource
    {
        EQUIPPED,
        BARE_BODY
    }

    /**
     * @return the model hitboxes are built from
     */
    @ConfigItem(
        keyName = "hitboxSource",
        name = "Hitbox source",
        description = "Experimental. Equipped: hitboxes from the model you see, including armour, capes and "
            + "the handegg. Bare body: from the player's body without equipment. Also changes which "
            + "contacts are detected.",
        section = DISPLAY_SECTION,
        position = 15
    )
    default HitboxSource hitboxSource()
    {
        return HitboxSource.EQUIPPED;
    }

    /** What decides a contact between two players. */
    enum ContactMode
    {
        CAPSULES,
        CAPSULES_AND_MESH,
        MESH
    }

    /**
     * @return how contacts are detected
     */
    @ConfigItem(
        keyName = "contactMode",
        name = "Contact mode",
        description = "For testing; this will be fixed to one mode for everyone later. Capsules: body-part "
            + "capsules overlap past the contact depth. Capsules and mesh: that, and the two models' "
            + "triangles actually touch. Mesh: any triangles of the two models touch.",
        section = DETECTION_SECTION,
        position = 0
    )
    default ContactMode contactMode()
    {
        return ContactMode.CAPSULES_AND_MESH;
    }

    @ConfigSection(
        name = "Interceptions",
        description = "A player catching a thrown handegg while in contact with another player. Shown on "
            + "your screen only; not reported yet.",
        position = 13
    )
    String INTERCEPTIONS_SECTION = "interceptions";

    /**
     * @return true to detect interceptions
     */
    @ConfigItem(
        keyName = "detectInterceptions",
        name = "Detect interceptions",
        description = "Detects a handegg caught after a throw by a player who is in contact with another "
            + "player. Needs contact detection running.",
        section = INTERCEPTIONS_SECTION,
        position = 14
    )
    default boolean detectInterceptions()
    {
        return true;
    }

    /**
     * @return true to post a chat message for each interception
     */
    @ConfigItem(
        keyName = "interceptionChatMessage",
        name = "Chat message",
        description = "Posts a game message naming who intercepted and who they were in contact with.",
        section = INTERCEPTIONS_SECTION,
        position = 15
    )
    default boolean interceptionChatMessage()
    {
        return true;
    }

    /**
     * @return true to highlight the receiver's tile on an interception
     */
    @ConfigItem(
        keyName = "highlightInterceptions",
        name = "Highlight interceptions",
        description = "Briefly highlights the tile under the player who intercepted.",
        section = INTERCEPTIONS_SECTION,
        position = 16
    )
    default boolean highlightInterceptions()
    {
        return true;
    }

    /**
     * @return colour of the interception highlight, including transparency
     */
    @Alpha
    @ConfigItem(
        keyName = "interceptionColor",
        name = "Interception colour",
        description = "Colour of the interception tile highlight and chat label.",
        section = INTERCEPTIONS_SECTION,
        position = 17
    )
    default Color interceptionColor()
    {
        return new Color(0, 200, 255, 180);
    }

    /**
     * @return true to log handegg projectiles, held weapons and contacts each tick
     */
    @ConfigItem(
        keyName = "debugLogging",
        name = "Debug logging",
        description = "Writes handegg projectiles, weapon changes, contacts and interception checks to the "
            + "RuneLite client log, for troubleshooting.",
        section = INTERCEPTIONS_SECTION,
        position = 18
    )
    default boolean debugLogging()
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
