package com.rfl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Observer mode overrides reporting for sending, and runs detection without it. */
public class ObserverGateTest
{
    @Test
    public void observerBlocksSendingEvenWithReportingOn()
    {
        assertTrue(RflPlugin.reportingAllowed(true, false));
        assertFalse(RflPlugin.reportingAllowed(true, true));
        assertFalse(RflPlugin.reportingAllowed(false, true));
        assertFalse(RflPlugin.reportingAllowed(false, false));
    }

    @Test
    public void observerDetectsWithReportingAndDetectContactsOff()
    {
        assertTrue(RflPlugin.detects(false, false, true, true, true));
        assertTrue(RflPlugin.detects(true, true, false, true, true));
        assertFalse(RflPlugin.detects(false, true, false, true, true));
        assertFalse(RflPlugin.detects(true, false, false, true, true));
        // Still only logged in, inside a house.
        assertFalse(RflPlugin.detects(false, false, true, false, true));
        assertFalse(RflPlugin.detects(false, false, true, true, false));
    }
}
