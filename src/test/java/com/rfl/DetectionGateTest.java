package com.rfl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Detection runs inside a house whenever Detect contacts is on; there is no reporting gate. */
public class DetectionGateTest
{
    @Test
    public void detectsLoggedInInsideAHouseWithDetectContactsOn()
    {
        assertTrue(RflPlugin.detects(true, true, true));
        assertFalse(RflPlugin.detects(false, true, true));
        assertFalse(RflPlugin.detects(true, false, true));
        assertFalse(RflPlugin.detects(true, true, false));
    }
}
