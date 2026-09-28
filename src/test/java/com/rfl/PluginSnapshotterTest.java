package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class PluginSnapshotterTest
{
    @Test
    public void hashIsStableForSameList()
    {
        List<PluginEntry> a = List.of(new PluginEntry("GPU", "BUILTIN"), new PluginEntry("Zoom", "PLUGIN_HUB"));
        assertEquals(PluginSnapshotter.hash(a), PluginSnapshotter.hash(new ArrayList<>(a)));
        assertEquals(64, PluginSnapshotter.hash(a).length());
    }

    @Test
    public void truncatesLongNamesTo64Chars()
    {
        String longName = "x".repeat(36) + "y".repeat(64);
        assertEquals(longName.substring(0, 64), PluginSnapshotter.truncateName(longName));
        assertEquals("GPU", PluginSnapshotter.truncateName("GPU"));
        assertEquals(PluginSnapshotter.hash(List.of(new PluginEntry(longName.substring(0, 64), "BUILTIN"))),
            PluginSnapshotter.hash(List.of(new PluginEntry(PluginSnapshotter.truncateName(longName), "BUILTIN"))));
    }

    @Test
    public void hashChangesWhenSourceChanges()
    {
        assertNotEquals(PluginSnapshotter.hash(List.of(new PluginEntry("GPU", "BUILTIN"))),
            PluginSnapshotter.hash(List.of(new PluginEntry("GPU", "UNOFFICIAL"))));
    }
}
