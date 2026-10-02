package com.rfl;

/**
 * A single enabled plugin as reported in an audit snapshot: its display name and where it
 * came from ({@code BUILTIN}, {@code PLUGIN_HUB}, or {@code UNOFFICIAL}).
 */
final class PluginEntry
{
    final String name;
    final String source;

    PluginEntry(final String name, final String source)
    {
        this.name = name;
        this.source = source;
    }
}
