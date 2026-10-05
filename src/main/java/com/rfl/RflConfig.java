package com.rfl;

import com.rfl.replay.ReplayFileName;

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

    // ---- Top ----

    /**
     * @return true to show the RFL sidebar panel
     */
    @ConfigItem(
        keyName = "showPanel",
        name = "Show RFL panel",
        description = "Adds the RFL panel to the sidebar: whether you are in a house, the replay recorder, this "
            + "session's collisions and incompletes, team assignments, Copy plugin history and Open folder.",
        position = 0
    )
    default boolean showPanel()
    {
        return false;
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
        return false;
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
        return false;
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
        return false;
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
        return false;
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
        return false;
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
        return false;
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
        return false;
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

    /**
     * @return the replay file name template; {@link ReplayFileName} expands and sanitises it
     */
    @ConfigItem(
        keyName = "replayFileName",
        name = "Replay file name",
        description = "Name for each replay file. Tokens: {date} (yyyy-MM-dd), {time} (HHmmss), {world}, "
            + "{player}. .rflr.gz is always added, characters a file name can't hold become _, and a taken "
            + "name gets -2, -3 and so on. Empty or invalid uses the default, {date}_{time}_w{world}.",
        section = REPLAYS_SECTION,
        position = 27
    )
    default String replayFileName()
    {
        return ReplayFileName.DEFAULT_TEMPLATE;
    }

    /**
     * @return true to add public overhead chat to replays
     */
    @ConfigItem(
        keyName = "recordOverheadChat",
        name = "Record overhead chat",
        description = "Adds the public chat shown over players' heads in the house (yours too) to replays, "
            + "for keeping time. A replay file you share then contains other players' public chat.",
        section = REPLAYS_SECTION,
        position = 28
    )
    default boolean recordOverheadChat()
    {
        return false;
    }
}
