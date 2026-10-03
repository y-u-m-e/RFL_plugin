package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/** {@link PluginSnapshotter}: names, sources and sorting, from public API only. */
public class PluginSnapshotterTest
{
    @PluginDescriptor(name = "true tile PLAYER indicators")
    static final class TrueTile extends Plugin
    {
    }

    @PluginDescriptor(name = "  Agility  ")
    static final class Agility extends Plugin
    {
    }

    @PluginDescriptor(name = "BLOCK TRACKER")
    static final class Blocks extends Plugin
    {
    }

    @Test
    public void entriesListEveryPluginSortedWithEnabledStateAndSource()
    {
        Plugin trueTile = new TrueTile();
        List<PluginEntry> entries = PluginSnapshotter.entries(List.of(trueTile, new Agility(), new Blocks()),
            p -> p != trueTile);
        assertEquals(3, entries.size());
        assertEquals("Agility", entries.get(0).name);
        assertEquals("BLOCK TRACKER", entries.get(1).name);
        assertEquals("true tile PLAYER indicators", entries.get(2).name);
        assertTrue(entries.get(0).enabled);
        assertFalse(entries.get(2).enabled);
        // Not in net.runelite.client.plugins and no Hub manifest.
        assertEquals(PluginEntry.SIDELOADED, entries.get(0).source);
    }

    @Test
    public void panelCountsEnabledAndDisabledPlugins()
    {
        PanelModel m = PanelModel.of(true, false, List.of(
            new PluginEntry("Agility", true, PluginEntry.BUILTIN),
            new PluginEntry("Block Tracker", true, PluginEntry.HUB),
            new PluginEntry("Zoom", false, PluginEntry.BUILTIN)), 0, 0, List.of(), List.of(), null, List.of(), null,
            java.time.ZoneOffset.UTC);
        assertEquals(2, m.getEnabledCount());
        assertEquals(1, m.getDisabledCount());
        assertTrue(m.isPluginsKnown());

        PanelModel empty = PanelModel.of(false, false, List.of(), 0, 0, List.of(), List.of(), null, List.of(), null,
            java.time.ZoneOffset.UTC);
        assertFalse(empty.isPluginsKnown());
    }
}
