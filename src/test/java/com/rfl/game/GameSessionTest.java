package com.rfl.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class GameSessionTest
{
    @Test
    public void emptyWhenNeverUpdated()
    {
        final GameSession session = new GameSession();

        assertEquals("", session.gameId());
        assertNull(session.detail());
        assertFalse(session.isHost("Anyone"));
    }

    @Test
    public void gameIdAndDetailReflectTheLastUpdate()
    {
        final GameSession session = new GameSession();
        final GameDetail detail = detailFor("game-1", "HostRsn");

        session.update(detail);

        assertEquals("game-1", session.gameId());
        assertEquals(detail, session.detail());
    }

    @Test
    public void isHostTrueOnlyForTheHostRsn()
    {
        final GameSession session = new GameSession();
        session.update(detailFor("game-1", "HostRsn"));

        assertTrue(session.isHost("HostRsn"));
        assertFalse(session.isHost("OtherRsn"));
        assertFalse(session.isHost(null));
    }

    @Test
    public void clearEmptiesGameId()
    {
        final GameSession session = new GameSession();
        session.update(detailFor("game-1", "HostRsn"));
        assertEquals("game-1", session.gameId());

        session.clear();

        assertEquals("", session.gameId());
        assertNull(session.detail());
        assertFalse(session.isHost("HostRsn"));
    }

    private static GameDetail detailFor(final String id, final String hostRsn)
    {
        final GameDetail.Game game = new GameDetail.Game(id, "Game", hostRsn, 301, "lobby", 1L, null, null);
        return new GameDetail(game, List.of(), List.of());
    }
}
