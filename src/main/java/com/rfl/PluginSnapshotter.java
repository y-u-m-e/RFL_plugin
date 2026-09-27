package com.rfl;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;

/**
 * Builds the enabled-plugin snapshot for the audit report: which plugins are enabled, where
 * each one came from, a stable hash of that list, and the {@code plugin_toggle} event fired
 * when a plugin is turned on or off. Ported unchanged from the pre-audit
 * {@code PlayerCollisionPlugin} (source classification logic, display name resolution).
 */
@Singleton
class PluginSnapshotter
{
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
     * Hashes a plugin entry list in stable sorted order so two clients with the same enabled
     * plugins produce the same hash.
     *
     * @param entries plugin entries to hash
     * @return SHA-256 hex hash of the snapshot
     */
    static String hash(final List<PluginEntry> entries)
    {
        final StringBuilder builder = new StringBuilder();
        for (final PluginEntry entry : entries)
        {
            builder.append(entry.name).append('|').append(entry.source).append('\n');
        }
        return sha256Hex(builder.toString());
    }

    private static String sha256Hex(final String value)
    {
        try
        {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            final StringBuilder builder = new StringBuilder();
            for (final byte b : hash)
            {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        }
        catch (NoSuchAlgorithmException ex)
        {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /**
     * Resolves display name for a plugin, preferring the descriptor annotation name.
     *
     * @param plugin plugin instance
     * @return display-ready plugin name
     */
    private static String pluginDisplayName(final Plugin plugin)
    {
        final PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
        if (descriptor != null && descriptor.name() != null && !descriptor.name().trim().isEmpty())
        {
            return descriptor.name().trim();
        }
        return plugin.getClass().getSimpleName();
    }

    /**
     * Classifies plugin source into built-in, plugin-hub, unofficial, or unknown.
     *
     * @param plugin plugin instance
     * @return source classification (BUILTIN, PLUGIN_HUB, UNOFFICIAL, UNKNOWN)
     */
    private static String pluginSource(final Plugin plugin)
    {
        final String className = plugin.getClass().getName();
        if (className.startsWith("net.runelite.client.plugins."))
        {
            return "BUILTIN";
        }

        final URL location = plugin.getClass().getProtectionDomain().getCodeSource() == null
            ? null
            : plugin.getClass().getProtectionDomain().getCodeSource().getLocation();

        final String locationText = location == null ? "" : location.toString().toLowerCase(Locale.ENGLISH);
        if (locationText.contains("plugin-hub") || locationText.contains("pluginhub"))
        {
            return "PLUGIN_HUB";
        }
        if (locationText.contains("sideload") || locationText.contains("externalplugin"))
        {
            return "UNOFFICIAL";
        }
        if (locationText.isEmpty())
        {
            return "UNKNOWN";
        }
        return "UNOFFICIAL";
    }
}
