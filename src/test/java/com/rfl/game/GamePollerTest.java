package com.rfl.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class GamePollerTest
{
    @Test
    public void delaysMatchInGameVsListPolling()
    {
        final GamePoller<String> poller = new GamePoller<>();

        assertEquals(1800L, poller.nextDelayMs(true));
        assertEquals(10000L, poller.nextDelayMs(false));
    }

    @Test
    public void noDataAndNoErrorBeforeTheFirstPoll()
    {
        final GamePoller<String> poller = new GamePoller<>();

        assertNull(poller.data());
        assertEquals("", poller.lastError());
    }

    @Test
    public void failedPollKeepsThePreviousDataAndSetsLastError()
    {
        final GamePoller<String> poller = new GamePoller<>();
        poller.onSuccess("good data");

        poller.onFailure("Can't reach the RFL API");

        assertEquals("good data", poller.data());
        assertEquals("Can't reach the RFL API", poller.lastError());
    }

    @Test
    public void aFollowingSuccessReplacesDataAndClearsTheError()
    {
        final GamePoller<String> poller = new GamePoller<>();
        poller.onSuccess("good data");
        poller.onFailure("Can't reach the RFL API");

        poller.onSuccess("fresh data");

        assertEquals("fresh data", poller.data());
        assertEquals("", poller.lastError());
    }
}
