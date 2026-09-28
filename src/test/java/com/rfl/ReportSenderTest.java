package com.rfl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReportSenderTest
{
    @Test
    public void requeueRules()
    {
        assertFalse(ReportSender.shouldRequeue(200));
        assertFalse(ReportSender.shouldRequeue(400));
        assertTrue(ReportSender.shouldRequeue(429));
        assertTrue(ReportSender.shouldRequeue(503));
    }
}
