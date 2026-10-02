package com.rfl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.client.events.PluginChanged;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/**
 * Builds the enabled-plugin snapshot for the audit report (which plugins are enabled and where
 * each one came from) and the {@code plugin_toggle} event fired when a plugin is turned on or off.
 *
 * <p>Only this client's own {@link PluginManager} is read, through public API: name, enabled state
 * and Hub manifest. No other plugin's classes, config or code location are inspected.
 */
@Singleton
class PluginSnapshotter
{
    static final int MAX_NAME = 64;

    private final PluginManager pluginManager;

    @Inject
    PluginSnapshotter(final PluginManager pluginManager)
    {
        this.pluginManager = pluginManager;
    }

    /**
     * Snapshots the currently enabled plugins, sorted case-insensitively by name.
     *
     * @return sorted enabled plugin entries
     */
    List<PluginEntry> snapshot()
    {
        final List<PluginEntry> entries = new ArrayList<>();
        for (final Plugin plugin : pluginManager.getPlugins())
        {
            if (!pluginManager.isPluginEnabled(plugin))
            {
                continue;
            }

            entries.add(new PluginEntry(pluginDisplayName(plugin), pluginSource(plugin)));
        }

        entries.sort(Comparator.comparing((PluginEntry entry) -> entry.name.toLowerCase(Locale.ENGLISH)));
        return entries;
    }

    /**
     * Builds the {@code plugin_toggle} event for a plugin enable/disable change.
     *
     * @param e   the RuneLite plugin-changed event
     * @param now server-relative timestamp (epoch ms)
     * @param tick current game tick
     * @return wire event describing the toggle
     */
    RflEvent toggleEvent(final PluginChanged e, final long now, final int tick)
    {
        return RflEvent.pluginToggle(now, tick, pluginDisplayName(e.getPlugin()), e.isLoaded());
    }

    /**
     * Display name from {@link Plugin#getName()} (the plugin's descriptor name), else its class name.
     *
     * @param plugin plugin instance
     * @return display-ready plugin name
     */
    private static String pluginDisplayName(final Plugin plugin)
    {
        final String name = plugin.getName();
        if (name != null && !name.trim().isEmpty())
        {
            return truncateName(name.trim());
        }
        return truncateName(plugin.getClass().getSimpleName());
    }

    /**
     * Cuts a plugin name to the server's 64-char string limit, so the server stores the name as sent.
     *
     * @param name plugin display name
     * @return at most the first 64 characters of {@code name}
     */
    static String truncateName(final String name)
    {
        return name.length() <= MAX_NAME ? name : name.substring(0, MAX_NAME);
    }

    /**
     * Classifies where a plugin came from, using only RuneLite's public API: core plugins live in
     * {@code net.runelite.client.plugins} (Hub jars may not use that namespace), Hub plugins have
     * a Hub manifest, and anything else was sideloaded.
     *
     * @param plugin plugin instance
     * @return BUILTIN, PLUGIN_HUB or UNOFFICIAL
     */
    private static String pluginSource(final Plugin plugin)
    {
        if (plugin.getClass().getName().startsWith("net.runelite.client.plugins."))
        {
            return "BUILTIN";
        }
        return ExternalPluginManager.getInternalName(plugin.getClass()) != null ? "PLUGIN_HUB" : "UNOFFICIAL";
    }
}
