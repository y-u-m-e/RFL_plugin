package com.playercollision;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

/**
 * User configuration for the RFL plugin audit workflow.
 */
@ConfigGroup("playercollision")
public interface PlayerCollisionConfig extends Config
{
    /**
     * Enables local match session monitoring with tamper-evident logs.
     *
     * @return true when session monitoring controls should be available
     */
    @ConfigItem(
        keyName = "enableMatchSessionMonitor",
        name = "Enable Match Session Monitor",
        description = "Enable local tamper-evident match logs for plugin compliance checks"
    )
    default boolean enableMatchSessionMonitor()
    {
        return true;
    }

    /**
     * Controls how often plugin snapshots are written into the session log.
     *
     * @return snapshot interval in seconds
     */
    @Range(min = 2, max = 30)
    @ConfigItem(
        keyName = "snapshotIntervalSeconds",
        name = "Snapshot Interval Seconds",
        description = "Seconds between plugin-list snapshots while a match session is active"
    )
    default int snapshotIntervalSeconds()
    {
        return 5;
    }

    /**
     * Controls whether a player right-click menu option is added for refs.
     *
     * @return true when the player menu option should be enabled
     */
    @ConfigItem(
        keyName = "enablePlayerMenuOption",
        name = "Enable Player Menu Option",
        description = "Adds a right-click player option for ref actions. Leave off for normal play."
    )
    default boolean enablePlayerMenuOption()
    {
        return false;
    }

    /**
     * Controls whether local users are notified when their own list contains blacklisted plugins.
     *
     * @return true when local blacklist notifications should be shown
     */
    @ConfigItem(
        keyName = "notifyLocalBlacklistHit",
        name = "Notify Local Blacklist Hits",
        description = "Warn you if your enabled plugin list contains a blacklisted entry"
    )
    default boolean notifyLocalBlacklistHit()
    {
        return true;
    }

    /**
     * Controls whether clipboard analysis sends a notifier popup for refs.
     *
     * @return true when ref analysis notifications should be shown
     */
    @ConfigItem(
        keyName = "notifyRefOnClipboardAnalysis",
        name = "Notify Ref On Clipboard Analysis",
        description = "Show a notification after checking a pasted plugin list"
    )
    default boolean notifyRefOnClipboardAnalysis()
    {
        return true;
    }

    /**
     * Controls whether source tags are included in copied exports.
     *
     * @return true when source labels should be included
     */
    @ConfigItem(
        keyName = "includeSourceInExport",
        name = "Include Source In Export",
        description = "Adds source tags such as BUILTIN, PLUGIN_HUB, and UNOFFICIAL in copied lists"
    )
    default boolean includeSourceInExport()
    {
        return true;
    }

}
