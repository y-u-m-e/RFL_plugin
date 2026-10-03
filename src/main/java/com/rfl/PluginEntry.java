package com.rfl;

/**
 * One installed plugin in a local plugin snapshot. Field names are the JSON keys:
 * {@code {"name":..,"enabled":..,"source":"builtin"|"hub"|"sideloaded"}}.
 */
final class PluginEntry
{
    static final String BUILTIN = "builtin";
    static final String HUB = "hub";
    static final String SIDELOADED = "sideloaded";

    final String name;
    final boolean enabled;
    final String source;

    PluginEntry(final String name, final boolean enabled, final String source)
    {
        this.name = name;
        this.enabled = enabled;
        this.source = source;
    }
}
