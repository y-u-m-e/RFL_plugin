package com.rfl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PluginSnapshotterTest
{
    @Test
    public void truncatesLongNamesTo64Chars()
    {
        String longName = "x".repeat(36) + "y".repeat(64);
        assertEquals(longName.substring(0, 64), PluginSnapshotter.truncateName(longName));
        assertEquals("GPU", PluginSnapshotter.truncateName("GPU"));
    }
}
