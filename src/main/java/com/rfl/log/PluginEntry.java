package com.rfl.log;

/**
 * One installed plugin in a local plugin snapshot. Field names are the JSON keys:
 * {@code {"name":..,"enabled":..,"source":"builtin"|"hub"|"sideloaded"}}.
 */
public final class PluginEntry
{
    public static final String BUILTIN = "builtin";
    public static final String HUB = "hub";
    static final String SIDELOADED = "sideloaded";

    final String name;
    final boolean enabled;
    final String source;

    public PluginEntry(final String name, final boolean enabled, final String source)
    {
        this.name = name;
        this.enabled = enabled;
        this.source = source;
    }
}
