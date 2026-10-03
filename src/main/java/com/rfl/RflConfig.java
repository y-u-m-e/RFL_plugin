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
 * User configuration for the RFL plugin. Key names are stored settings: rename only with a
 * migration.
 */
@ConfigGroup(RflConfig.GROUP)
public interface RflConfig extends Config
{
    /** Config group name. */
    String GROUP = "rfl";

    @ConfigSection(
        name = "Features",
        description = "Each feature can be turned off. Any feature that is off is reported and shown "
            + "publicly on rfl.gg as a flag.",
        position = 4
    )
    String FEATURES_SECTION = "features";

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
        description = "Briefly highlights the tile under each of your reported contacts. "
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
     * @return true to detect and send the local player's own contacts inside a player-owned house
     */
    @ConfigItem(
        keyName = "reportContacts",
        name = "Detect contacts",
        description = "Inside a player-owned house, detects when your character touches another player while "
            + "either of you holds a handegg and sends the time, tile and depth; also sends the time and tile "
            + "when two other players collide with a handegg. Never sends another player's name. Turning this "
            + "off is reported and shown publicly on rfl.gg as a flag.",
        section = FEATURES_SECTION,
        position = 6
    )
    default boolean reportContacts()
    {
        return true;
    }

    /**
     * @return true to append each collision and interception to the local day file
     */
    @ConfigItem(
        keyName = "saveCollisions",
        name = "Save collisions",
        description = "Saves every handegg collision and interception to .runelite/rfl/collisions, one file "
            + "per day. Stays on this computer.",
        section = FEATURES_SECTION,
        position = 7
    )
    default boolean saveCollisions()
    {
        return true;
    }

    /**
     * @return true to fill the triangles where two players' models touch
     */
    @ConfigItem(
        keyName = "showTouchingTriangles",
        name = "Show touching triangles",
        description = "While two players' models touch, fills the exact triangles that touch in the contact "
            + "colour, for every player in view. Shown without the full wireframe from Show hitboxes.",
        section = DISPLAY_SECTION,
        position = 12
    )
    default boolean showTouchingTriangles()
    {
        return true;
    }

    /**
     * @return true to draw every player's mesh wireframe and touching triangles
     */
    @ConfigItem(
        keyName = "showHitboxes",
        name = "Show hitboxes",
        description = "Draws a wireframe of the model triangles contacts are detected from, for every player "
            + "in view while contact detection runs. Triangles touching another player's are filled in the "
            + "contact colour.",
        section = DISPLAY_SECTION,
        position = 13
    )
    default boolean showHitboxes()
    {
        return false;
    }

    /**
     * @return colour of the mesh wireframe, including transparency
     */
    @Alpha
    @ConfigItem(
        keyName = "hitboxColor",
        name = "Hitbox colour",
        description = "Colour of the mesh wireframe.",
        section = DISPLAY_SECTION,
        position = 15
    )
    default Color hitboxColor()
    {
        return new Color(255, 255, 255, 57);
    }

    /** Which model the contact mesh is built from. */
    enum HitboxSource
    {
        EQUIPPED,
        BARE_BODY
    }

    /**
     * @return the model the contact mesh is built from
     */
    @ConfigItem(
        keyName = "hitboxSource",
        name = "Hitbox source",
        description = "Experimental. Equipped: the model you see, including armour, capes and the handegg. "
            + "Bare body: the player's body without equipment. Changes which contacts are detected.",
        section = DISPLAY_SECTION,
        position = 16
    )
    default HitboxSource hitboxSource()
    {
        return HitboxSource.EQUIPPED;
    }

    /**
     * @return true to show the RFL Debug sidebar panel
     */
    @ConfigItem(
        keyName = "showDebugPanel",
        name = "Show debug panel",
        description = "Adds an RFL Debug panel to the sidebar: what contact detection sees right now, "
            + "recent contact and interception decisions, and how long detection takes.",
        section = DISPLAY_SECTION,
        position = 17
    )
    default boolean showDebugPanel()
    {
        return false;
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
}
