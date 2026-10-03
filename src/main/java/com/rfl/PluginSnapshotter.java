package com.rfl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/**
 * Lists this client's own installed plugins (name, enabled, where it came from) for the local
 * plugin log and the RFL panel. Only this client's {@link PluginManager} is read, through public
 * API: {@link PluginManager#getPlugins()}, {@link PluginManager#isPluginEnabled},
 * {@link Plugin#getName()} and {@link ExternalPluginManager#getInternalName}. No other plugin's
 * classes, config or code are inspected, and nothing is sent anywhere.
 */
@Singleton
final class PluginSnapshotter
{
    static final int MAX_NAME = 64;

    /** Plugins the league bans, as lower-case display names. */
    static final Set<String> BANNED = Set.of("block tracker", "true tile player indicators");

    private final PluginManager pluginManager;

    @Inject
    PluginSnapshotter(final PluginManager pluginManager)
    {
        this.pluginManager = pluginManager;
    }

    /** Every installed plugin, enabled or not, sorted case-insensitively by name. */
    List<PluginEntry> snapshot()
    {
        return entries(pluginManager.getPlugins(), pluginManager::isPluginEnabled);
    }

    static List<PluginEntry> entries(final Collection<Plugin> plugins, final Predicate<Plugin> enabled)
    {
        final List<PluginEntry> entries = new ArrayList<>();
        for (final Plugin plugin : plugins)
        {
            entries.add(new PluginEntry(displayName(plugin), enabled.test(plugin), source(plugin)));
        }
        entries.sort(Comparator.comparing((PluginEntry e) -> e.name.toLowerCase(Locale.ENGLISH)));
        return entries;
    }

    /** Whether a display name is on the banned list, ignoring case and surrounding spaces. */
    static boolean isBanned(final String name)
    {
        return name != null && BANNED.contains(name.trim().toLowerCase(Locale.ENGLISH));
    }

    /** Names of the enabled banned plugins in a list, in list order. */
    static List<String> enabledBanned(final List<PluginEntry> entries)
    {
        final List<String> out = new ArrayList<>();
        for (final PluginEntry e : entries)
        {
            if (e.enabled && isBanned(e.name))
            {
                out.add(e.name);
            }
        }
        return out;
    }

    /** {@link Plugin#getName()} (the descriptor name), else the class name; at most 64 characters. */
    static String displayName(final Plugin plugin)
    {
        final String name = plugin.getName();
        final String shown = name != null && !name.trim().isEmpty() ? name.trim()
            : plugin.getClass().getSimpleName();
        return shown.length() <= MAX_NAME ? shown : shown.substring(0, MAX_NAME);
    }

    /**
     * Core plugins live in {@code net.runelite.client.plugins} (Hub jars may not use that
     * namespace), Hub plugins have a Hub manifest, and anything else was sideloaded.
     */
    static String source(final Plugin plugin)
    {
        if (plugin.getClass().getName().startsWith("net.runelite.client.plugins."))
        {
            return PluginEntry.BUILTIN;
        }
        return ExternalPluginManager.getInternalName(plugin.getClass()) != null ? PluginEntry.HUB
            : PluginEntry.SIDELOADED;
    }
}
