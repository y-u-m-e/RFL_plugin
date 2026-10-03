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
 * User configuration for the RFL plugin. Everything stays on this computer. Key names are stored
 * settings: rename only with a migration ({@code reportContacts} is the Detect contacts switch).
 */
@ConfigGroup(RflConfig.GROUP)
public interface RflConfig extends Config
{
    /** Config group name. */
    String GROUP = "rfl";

    @ConfigSection(
        name = "Detection",
        description = "Handegg collision detection inside a player-owned house, and saving what it finds.",
        position = 0
    )
    String DETECTION_SECTION = "detection";

    @ConfigSection(
        name = "Display",
        description = "What the plugin draws on your screen.",
        position = 10
    )
    String DISPLAY_SECTION = "display";

    @ConfigSection(
        name = "Interceptions",
        description = "A player catching a thrown handegg while in contact with another player.",
        position = 20
    )
    String INTERCEPTIONS_SECTION = "interceptions";

    @ConfigSection(
        name = "Replays",
        description = "Local replay files of player-owned house visits, for the RFL replay viewer.",
        position = 25
    )
    String REPLAYS_SECTION = "replays";

    @ConfigSection(
        name = "Debug",
        description = "Troubleshooting tools.",
        position = 30,
        closedByDefault = true
    )
    String DEBUG_SECTION = "debug";

    // ---- Detection ----

    /**
     * @return true to detect handegg collisions inside a player-owned house
     */
    @ConfigItem(
        keyName = "reportContacts",
        name = "Detect contacts",
        description = "Inside a player-owned house, detects when any two players in view touch while either "
            + "of them holds a handegg. Interceptions need this on.",
        section = DETECTION_SECTION,
        position = 1
    )
    default boolean reportContacts()
    {
        return true;
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
        section = DETECTION_SECTION,
        position = 2
    )
    default HitboxSource hitboxSource()
    {
        return HitboxSource.EQUIPPED;
    }

    /**
     * @return true to append each collision and interception to the local day file
     */
    @ConfigItem(
        keyName = "saveCollisions",
        name = "Save collisions",
        description = "Saves every handegg collision (both names, who held the handegg, time, tile) and every "
            + "interception to .runelite/rfl/collisions, one file per day. Stays on this computer.",
        section = DETECTION_SECTION,
        position = 3
    )
    default boolean saveCollisions()
    {
        return true;
    }

    // ---- Display ----

    /**
     * @return true to briefly highlight the tile under each collision as it starts
     */
    @ConfigItem(
        keyName = "highlightContacts",
        name = "Highlight contacts",
        description = "Briefly highlights the tile under each handegg collision as it starts.",
        section = DISPLAY_SECTION,
        position = 11
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
        description = "Colour of the contact tile highlight, and of touching triangles.",
        section = DISPLAY_SECTION,
        position = 12
    )
    default Color contactHighlightColor()
    {
        return new Color(255, 230, 0, 153);
    }

    /**
     * @return how long a contact or interception highlight takes to fade out, in milliseconds
     */
    @Range(min = 200, max = 5000)
    @Units(Units.MILLISECONDS)
    @ConfigItem(
        keyName = "highlightDurationMs",
        name = "Highlight duration",
        description = "How long a contact or interception tile highlight takes to fade out.",
        section = DISPLAY_SECTION,
        position = 13
    )
    default int highlightDurationMs()
    {
        return 1200;
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
        position = 14
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
            + "in view while detection runs. Triangles touching another player's are filled in the contact "
            + "colour.",
        section = DISPLAY_SECTION,
        position = 15
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
        description = "Colour of the Show hitboxes wireframe.",
        section = DISPLAY_SECTION,
        position = 16
    )
    default Color hitboxColor()
    {
        return new Color(255, 255, 255, 57);
    }

    // ---- Interceptions ----

    /**
     * @return true to detect interceptions
     */
    @ConfigItem(
        keyName = "detectInterceptions",
        name = "Detect interceptions",
        description = "Detects a handegg caught after a throw by a player who is in contact with another "
            + "player. Needs Detect contacts on. Saved with collisions when Save collisions is on.",
        section = INTERCEPTIONS_SECTION,
        position = 21
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
        position = 22
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
        position = 23
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
        position = 24
    )
    default Color interceptionColor()
    {
        return new Color(0, 200, 255, 180);
    }

    // ---- Replays ----

    /**
     * @return true to record each player-owned house visit to a local replay file
     */
    @ConfigItem(
        keyName = "recordReplays",
        name = "Record replays",
        description = "Records every player-owned house visit to a local replay file (rfl/replays in the "
            + "RuneLite folder) for the RFL replay viewer. Nothing is sent anywhere.",
        section = REPLAYS_SECTION,
        position = 26
    )
    default boolean recordReplays()
    {
        return false;
    }

    // ---- Debug ----

    /**
     * @return true to show the RFL Debug sidebar panel
     */
    @ConfigItem(
        keyName = "showDebugPanel",
        name = "Show debug panel",
        description = "Adds an RFL Debug panel to the sidebar: what detection sees right now, collisions in "
            + "progress, the latest saved collisions with an Open folder button, recent interception "
            + "decisions, and how long detection takes.",
        section = DEBUG_SECTION,
        position = 31
    )
    default boolean showDebugPanel()
    {
        return false;
    }

    /**
     * @return true to log handegg projectiles, held weapons and contacts each tick
     */
    @ConfigItem(
        keyName = "debugLogging",
        name = "Debug logging",
        description = "Writes handegg projectiles, weapon changes, contacts and interception checks to the "
            + "RuneLite client log, for troubleshooting.",
        section = DEBUG_SECTION,
        position = 32
    )
    default boolean debugLogging()
    {
        return false;
    }
}
