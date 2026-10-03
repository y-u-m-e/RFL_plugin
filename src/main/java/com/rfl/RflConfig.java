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
        position = 1
    )
    String DETECTION_SECTION = "detection";

    @ConfigSection(
        name = "Display",
        description = "What the plugin draws on your screen.",
        position = 10
    )
    String DISPLAY_SECTION = "display";

    @ConfigSection(
        name = "Incompletes",
        description = "A catch made while in contact with another player is an incomplete pass.",
        position = 20
    )
    String INCOMPLETES_SECTION = "incompletes";

    @ConfigSection(
        name = "Replays",
        description = "Local replay files of player-owned house visits, for the RFL replay viewer.",
        position = 25
    )
    String REPLAYS_SECTION = "replays";

    @ConfigSection(
        name = "Audit",
        description = "Your own plugin list, saved on this computer for league refs.",
        position = 27
    )
    String AUDIT_SECTION = "audit";

    @ConfigSection(
        name = "Debug",
        description = "Troubleshooting tools.",
        position = 30,
        closedByDefault = true
    )
    String DEBUG_SECTION = "debug";

    // ---- Top ----

    /**
     * @return true to show the RFL sidebar panel
     */
    @ConfigItem(
        keyName = "showPanel",
        name = "Show RFL panel",
        description = "Adds the RFL panel to the sidebar: your plugin list, what detection sees right now, "
            + "collisions in progress, the latest saved collisions with an Open folder button, and recent "
            + "incomplete decisions.",
        position = 0
    )
    default boolean showPanel()
    {
        return true;
    }

    // ---- Detection ----

    /**
     * @return true to detect handegg collisions inside a player-owned house
     */
    @ConfigItem(
        keyName = "reportContacts",
        name = "Detect contacts",
        description = "Inside a player-owned house, detects when any two players in view touch while either "
            + "of them holds a handegg. Incompletes need this on.",
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
     * @return true to append each collision and incomplete to the local day file
     */
    @ConfigItem(
        keyName = "saveCollisions",
        name = "Save collisions",
        description = "Saves every handegg collision (both names, who held the handegg, time, tile) and every "
            + "incomplete to .runelite/rfl/collisions, one file per day. Stays on this computer.",
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
     * @return how long a contact or incomplete highlight takes to fade out, in milliseconds
     */
    @Range(min = 200, max = 5000)
    @Units(Units.MILLISECONDS)
    @ConfigItem(
        keyName = "highlightDurationMs",
        name = "Highlight duration",
        description = "How long a contact or incomplete tile highlight takes to fade out.",
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
            + "colour, for collisions where at least one player holds a handegg. Shown without the full "
            + "wireframe from Show hitboxes.",
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
            + "colour, for collisions where at least one player holds a handegg.",
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

    // ---- Incompletes ----

    /**
     * @return true to detect incompletes
     */
    @ConfigItem(
        keyName = "detectIncompletes",
        name = "Detect incompletes",
        description = "A catch made while in contact with another player is an incomplete pass. Needs Detect "
            + "contacts on. Saved with collisions when Save collisions is on.",
        section = INCOMPLETES_SECTION,
        position = 21
    )
    default boolean detectIncompletes()
    {
        return true;
    }

    /**
     * @return true to post a chat message for each incomplete
     */
    @ConfigItem(
        keyName = "incompleteChatMessage",
        name = "Chat message",
        description = "Posts a game message naming who caught the handegg in contact and who they were in "
            + "contact with.",
        section = INCOMPLETES_SECTION,
        position = 22
    )
    default boolean incompleteChatMessage()
    {
        return true;
    }

    /**
     * @return true to highlight the receiver's tile on an incomplete
     */
    @ConfigItem(
        keyName = "highlightIncompletes",
        name = "Highlight incompletes",
        description = "Briefly highlights the tile under the player who caught the handegg in contact.",
        section = INCOMPLETES_SECTION,
        position = 23
    )
    default boolean highlightIncompletes()
    {
        return true;
    }

    /**
     * @return colour of the incomplete highlight, including transparency
     */
    @Alpha
    @ConfigItem(
        keyName = "incompleteColor",
        name = "Incomplete colour",
        description = "Colour of the incomplete tile highlight and chat label.",
        section = INCOMPLETES_SECTION,
        position = 24
    )
    default Color incompleteColor()
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

    // ---- Audit ----

    /**
     * @return true to save the local user's own plugin list and plugin toggles to the local day file
     */
    @ConfigItem(
        keyName = "logPluginStats",
        name = "Log plugin stats",
        description = "Saves your own plugin list on this computer (rfl/plugins in the RuneLite folder) for "
            + "league refs: every plugin and whether it is on when you enter a player-owned house, and each "
            + "plugin you turn on or off. Nothing is sent anywhere.",
        section = AUDIT_SECTION,
        position = 28
    )
    default boolean logPluginStats()
    {
        return true;
    }

    // ---- Debug ----

    /**
     * @return true to log handegg projectiles, held weapons and contacts each tick
     */
    @ConfigItem(
        keyName = "debugLogging",
        name = "Debug logging",
        description = "Writes handegg projectiles, weapon changes, contacts and incomplete checks to the "
            + "RuneLite client log, for troubleshooting.",
        section = DEBUG_SECTION,
        position = 32
    )
    default boolean debugLogging()
    {
        return false;
    }
}
