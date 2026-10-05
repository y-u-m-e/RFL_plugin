package com.rfl.log;

import com.rfl.replay.TrueTile;

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
}
