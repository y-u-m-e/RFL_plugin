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
        assertNull(GamePanel.exitNotice(detail("lobby", "Host", "Me"), "Me", "Me"));
    }

    @Test
    public void removedWhenOffRoster()
    {
        assertEquals(GamePanel.REMOVED, GamePanel.exitNotice(detail("live", "Host"), "Me", "Me"));
    }

    @Test
    public void endedBeatsRoster()
    {
        assertEquals(GamePanel.GAME_ENDED, GamePanel.exitNotice(detail("ended", "Me"), "Me", "Me"));
        assertEquals(GamePanel.GAME_ENDED, GamePanel.exitNotice(null, "Me", "Me"));
    }

    @Test
    public void unknownRsnIsNotJudged()
    {
        assertNull(GamePanel.exitNotice(detail("lobby", "Host"), null, "Me"));
    }

    @Test
    public void pollsOnlyWhenReportingAndNotDisposed()
    {
        org.junit.Assert.assertTrue(GamePanel.mayPoll(true, false));
        org.junit.Assert.assertFalse(GamePanel.mayPoll(false, false));
        org.junit.Assert.assertFalse(GamePanel.mayPoll(true, true));
        org.junit.Assert.assertFalse(GamePanel.mayPoll(false, true));
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
        assertEquals(" <HTML><img src=x>", PanelWidgets.plain("<HTML><img src=x>"));
        assertEquals("Red", PanelWidgets.plain("Red"));
        assertEquals("", PanelWidgets.plain(null));
    }

    @Test
    public void accountSwitchIsNotReadAsRemoved()
    {
        assertEquals(GamePanel.SWITCHED, GamePanel.exitNotice(detail("lobby", "Host", "Me"), "Alt", "Me"));
        assertNull(GamePanel.exitNotice(detail("lobby", "Host", "Me"), "Me", "Me"));
        assertEquals(GamePanel.REMOVED, GamePanel.exitNotice(detail("lobby", "Host"), "Me", "Me"));
        assertEquals(GamePanel.GAME_ENDED, GamePanel.exitNotice(detail("ended", "Me"), "Alt", "Me"));
    }

    @Test
    public void onlyTheHostSeesThePassphrase()
    {
        assertEquals("brave-otter-lamp", GamePanel.passphraseText(true, "brave-otter-lamp"));
        assertEquals(GamePanel.ASK_HOST, GamePanel.passphraseText(false, "brave-otter-lamp"));
        assertEquals(GamePanel.ASK_HOST, GamePanel.passphraseText(false, ""));
        assertEquals(GamePanel.HOST_NO_PASSPHRASE, GamePanel.passphraseText(true, ""));
    }

    @Test
    public void rosterSkipsPlayersWithoutAnRsn()
    {
        final GameDetail d = detail("lobby", "Host", "Me");
        d.players.add(new GameDetail.Player(null, "A", 1L));
        d.players.add(null);
        assertEquals(2, GamePanel.roster(d).size());
        d.players = null;
        assertEquals(0, GamePanel.roster(d).size());
    }
}
