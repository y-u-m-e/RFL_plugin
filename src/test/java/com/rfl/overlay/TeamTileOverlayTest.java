package com.rfl.overlay;

import com.rfl.panel.RflPanel;
import com.rfl.teams.Teams;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import org.junit.Test;

/** {@link TeamTileOverlay}: team tiles only while the Teams view is on screen, never for the unassigned. */
public class TeamTileOverlayTest
{
    @Test
    public void activeOnlyWithThePanelOpenOnTheTeamsView()
    {
        assertTrue(TeamTileOverlay.active(true, RflPanel.View.TEAMS));
        assertFalse("sidebar on another plugin or closed", TeamTileOverlay.active(false, RflPanel.View.TEAMS));
        assertFalse(TeamTileOverlay.active(true, RflPanel.View.COLLISIONS));
        assertFalse(TeamTileOverlay.active(true, RflPanel.View.INCOMPLETES));
        assertFalse(TeamTileOverlay.active(true, null));
    }

    @Test
    public void unassignedPlayersAreSkipped()
    {
        Map<String, Teams.Team> teams = Map.of("Amy", Teams.Team.A, "Bob", Teams.Team.B);
        assertEquals(Teams.Team.A, TeamTileOverlay.tileTeam("Amy", teams::get));
        assertEquals(Teams.Team.B, TeamTileOverlay.tileTeam("Bob", teams::get));
        assertNull(TeamTileOverlay.tileTeam("Zed", teams::get));
        assertNull(TeamTileOverlay.tileTeam(null, teams::get));
    }

    @Test
    public void thePanelReportsShowingOnlyWhileActiveOnTeams() throws Exception
    {
        List<Boolean> heard = new ArrayList<>();
        AtomicReference<RflPanel> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
        {
            RflPanel panel = new RflPanel(() -> { }, () -> { }, () -> { }, () -> { }, () -> { }, (n, t) -> { },
                () -> { });
            ref.set(panel);
            panel.setTeamsShowing(heard::add);
            panel.selectView(RflPanel.View.TEAMS);
            panel.onActivate();
            panel.selectView(RflPanel.View.COLLISIONS);
            panel.selectView(RflPanel.View.TEAMS);
            panel.onDeactivate();
        });
        assertEquals(List.of(false, false, true, false, true, false), heard);
    }
}
