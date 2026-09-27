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
    public void hashChangesWhenSourceChanges()
    {
        assertNotEquals(PluginSnapshotter.hash(List.of(new PluginEntry("GPU", "BUILTIN"))),
            PluginSnapshotter.hash(List.of(new PluginEntry("GPU", "UNOFFICIAL"))));
    }
}
