package com.rfl.contact;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Detection runs inside a house whenever Detect contacts is on; there is no reporting gate. */
public class DetectionGateTest
{
    @Test
    public void detectsLoggedInInsideAHouseWithDetectContactsOn()
    {
        assertTrue(ContactDetector.detects(true, true, true));
        assertFalse(ContactDetector.detects(false, true, true));
        assertFalse(ContactDetector.detects(true, false, true));
        assertFalse(ContactDetector.detects(true, true, false));
    }
}
