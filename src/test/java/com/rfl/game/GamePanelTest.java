package com.rfl.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.awt.Color;
import java.util.List;

import org.junit.Test;

public class GamePanelTest
{
    private static GameDetail detail(final String state, final String... rsns)
    {
        final List<GameDetail.Player> players = new java.util.ArrayList<>();
        for (final String rsn : rsns)
        {
            players.add(new GameDetail.Player(rsn, "", 1L));
        }
        return new GameDetail(new GameDetail.Game("g1", "Game", "Host", 301, state, 1L, null, null),
            List.of(new GameDetail.Team("A", "Red", "#D9363E")), players);
    }

    @Test
    public void stillInGameWhenOnRoster()
    {
        assertNull(GamePanel.exitNotice(detail("lobby", "Host", "Me"), "Me"));
    }

    @Test
    public void removedWhenOffRoster()
    {
        assertEquals(GamePanel.REMOVED, GamePanel.exitNotice(detail("live", "Host"), "Me"));
    }

    @Test
    public void endedBeatsRoster()
    {
        assertEquals(GamePanel.GAME_ENDED, GamePanel.exitNotice(detail("ended", "Me"), "Me"));
        assertEquals(GamePanel.GAME_ENDED, GamePanel.exitNotice(null, "Me"));
    }

    @Test
    public void unknownRsnIsNotJudged()
    {
        assertNull(GamePanel.exitNotice(detail("lobby", "Host"), null));
    }

    @Test
    public void hostStateButtonFollowsState()
    {
        assertEquals("Start", GamePanel.hostStateButton("lobby"));
        assertEquals("End", GamePanel.hostStateButton("live"));
        assertNull(GamePanel.hostStateButton("ended"));
    }

    @Test
    public void coloursRoundTripAndRejectMalformed()
    {
        final Color red = GamePanel.parseColor("#D9363E", Color.GRAY);
        assertEquals(new Color(0xD9, 0x36, 0x3E), red);
        assertEquals("#D9363E", GamePanel.toHex(red));
        assertEquals(Color.GRAY, GamePanel.parseColor("red", Color.GRAY));
        assertEquals(Color.GRAY, GamePanel.parseColor("#12345", Color.GRAY));
        assertEquals(Color.GRAY, GamePanel.parseColor(null, Color.GRAY));
    }

    @Test
    public void serverTextCannotBecomeHtml()
    {
        assertEquals(" <HTML><img src=x>", GamePanel.plain("<HTML><img src=x>"));
        assertEquals("Red", GamePanel.plain("Red"));
        assertEquals("", GamePanel.plain(null));
    }
}
