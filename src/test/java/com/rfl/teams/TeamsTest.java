package com.rfl.teams;

import com.rfl.Fixtures;
import com.rfl.contact.TileRef;
import sh.yumekui.toolkit.text.PlayerNames;
import com.rfl.contact.ContactTracker;
import com.rfl.panel.PanelModel;
import sh.yumekui.toolkit.geom.TriangleMesh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** {@link Teams}: local A / B / unassigned, the opposite-teams gate, persistence and the menu. */
public class TeamsTest
{
    private static final Gson GSON = new GsonBuilder().create();

    /** An in-memory {@link Teams.Store}, like the ConfigManager value. */
    private static final class MemoryStore implements Teams.Store
    {
        String json;

        @Override
        public String load()
        {
            return json;
        }

        @Override
        public void save(String json)
        {
            this.json = json;
        }
    }

    private static Teams teams(MemoryStore store)
    {
        Teams t = new Teams(GSON, store);
        t.load();
        return t;
    }

    @Test
    public void onlyOppositeTeamsCount()
    {
        Teams.Team a = Teams.Team.A;
        Teams.Team b = Teams.Team.B;
        assertTrue("A-B", Teams.opposing(a, b));
        assertTrue("B-A", Teams.opposing(b, a));
        assertFalse("A-A", Teams.opposing(a, a));
        assertFalse("B-B", Teams.opposing(b, b));
        assertFalse("A-unassigned", Teams.opposing(a, null));
        assertFalse("unassigned-B", Teams.opposing(null, b));
        assertFalse("unassigned-unassigned", Teams.opposing(null, null));
    }

    @Test
    public void assignmentsSurviveARestart()
    {
        MemoryStore store = new MemoryStore();
        Teams first = teams(store);
        assertTrue(first.assign("Ref Bob", Teams.Team.A));
        assertTrue(first.assign("Amy", Teams.Team.B));
        assertFalse("no change, no save", first.assign("ref bob", Teams.Team.A));
        assertEquals("{\"Amy\":\"B\",\"Ref Bob\":\"A\"}", store.json);

        Teams second = teams(store);
        assertEquals(Teams.Team.A, second.team("Ref Bob"));
        assertEquals(Teams.Team.B, second.team("Amy"));
        assertNull(second.team("Zed"));
        assertEquals(Map.of("Ref Bob", Teams.Team.A, "Amy", Teams.Team.B), second.assigned());

        assertTrue(second.assign("Amy", null));
        assertEquals("{\"Ref Bob\":\"A\"}", store.json);
        assertEquals(Map.of("Ref Bob", Teams.Team.A), teams(store).assigned());
    }

    @Test
    public void badSavedValuesStartEmptyOrSkipEntries()
    {
        MemoryStore store = new MemoryStore();
        store.json = "{torn";
        assertTrue(teams(store).assigned().isEmpty());
        store.json = "{\"Amy\":\"C\",\"Bob\":\"B\",\"Cy\":5}";
        assertEquals(Map.of("Bob", Teams.Team.B), teams(store).assigned());
    }

    @Test
    public void clearTeamsUnassignsEveryoneRemovesTheSavedValueAndTellsTheListener()
    {
        MemoryStore store = new MemoryStore();
        Teams t = teams(store);
        List<String> heard = new ArrayList<>();
        t.setListener(new Teams.Listener()
        {
            @Override
            public void changed(String name)
            {
                heard.add("changed " + name);
            }

            @Override
            public void cleared()
            {
                heard.add("cleared");
            }
        });
        t.assign("Amy", Teams.Team.A);
        t.assign("Bob", Teams.Team.B);
        long version = t.version();
        t.clear();
        assertNull(t.team("Amy"));
        assertNull(t.team("Bob"));
        assertTrue(t.assigned().isEmpty());
        assertNull("the saved value is removed", store.json);
        assertTrue(t.version() != version);
        assertEquals(Arrays.asList("changed Amy", "changed Bob", "cleared"), heard);
        assertTrue("still empty after a restart", teams(store).assigned().isEmpty());
    }

    @Test
    public void namesMatchTheWayRuneLiteDoes()
    {
        assertEquals("ref bob", PlayerNames.matchKey("Ref Bob"));
        assertEquals(PlayerNames.matchKey("Ref Bob"), PlayerNames.matchKey("ref_bob"));
        assertEquals(PlayerNames.matchKey("Ref Bob"), PlayerNames.matchKey("REF BOB"));
        assertEquals(PlayerNames.matchKey("Ref Bob"), PlayerNames.matchKey("Ref-Bob"));
        assertNull(PlayerNames.matchKey(null));

        Teams t = teams(new MemoryStore());
        t.assign("Ref_Bob", Teams.Team.A);
        assertEquals("one player, not two", Teams.Team.A, t.team("ref bob"));
        t.assign("REF BOB", Teams.Team.B);
        assertEquals(1, t.assigned().size());
        assertEquals("the latest spelling is shown", Map.of("REF BOB", Teams.Team.B), t.assigned());
    }

    @Test
    public void menuEntriesOnlyForPlayersInAHouseAndOnlyThoseThatChangeSomething()
    {
        assertEquals(Collections.emptyList(), Teams.menuChoices(false, true, null));
        assertEquals(Collections.emptyList(), Teams.menuChoices(true, false, null));
        assertEquals(Arrays.asList(Teams.Team.A, Teams.Team.B), Teams.menuChoices(true, true, null));
        assertEquals(Arrays.asList(Teams.Team.B, null), Teams.menuChoices(true, true, Teams.Team.A));
        assertEquals(Arrays.asList(Teams.Team.A, null), Teams.menuChoices(true, true, Teams.Team.B));
        assertEquals("RFL: Team A", Teams.menuLabel(Teams.Team.A));
        assertEquals("RFL: Unassign", Teams.menuLabel(null));
        assertTrue(TeamMenu.isPlayerOption(net.runelite.api.MenuAction.PLAYER_FIRST_OPTION));
        assertFalse(TeamMenu.isPlayerOption(net.runelite.api.MenuAction.NPC_FIRST_OPTION));
        assertFalse(TeamMenu.isPlayerOption(null));
    }

    @Test
    public void teamsViewListsPlayersHereAndAbsentAssignedOnes()
    {
        PanelModel.TeamsState s = PanelModel.teams(List.of("Zed", "amy"), Map.of("Amy", Teams.Team.A,
            "Gone Guy", Teams.Team.B), true);
        assertEquals(List.of(new PanelModel.TeamRow("Amy", Teams.Team.A, true),
            new PanelModel.TeamRow("Gone Guy", Teams.Team.B, false),
            new PanelModel.TeamRow("Zed", null, true)), s.getRows());
        assertEquals(1, s.getTeamA());
        assertEquals(1, s.getTeamB());
        assertFalse("both teams have someone", s.isHint());
        assertTrue("a team is empty while detecting", PanelModel.teams(List.of("Zed"), Map.of("Amy", Teams.Team.A),
            true).isHint());
        assertFalse("no hint with detection off", PanelModel.teams(List.of(), Map.of(), false).isHint());
    }

    @Test
    public void theTrackerNeverChecksAPairTheTeamGateRejects()
    {
        Map<String, TriangleMesh> touching = Map.of("A", Fixtures.mesh(Fixtures.WALL), "B", Fixtures.mesh(Fixtures.THROUGH));
        Set<String> holders = Set.of("A", "B");

        ContactTracker sameTeam = tracker();
        sameTeam.setPairFilter((x, y) -> Teams.opposing(Teams.Team.A, Teams.Team.A));
        assertTrue(sameTeam.update(touching, holders, 1000, 5, true).isEmpty());
        assertTrue(sameTeam.overlaps().isEmpty());

        ContactTracker opposite = tracker();
        opposite.setPairFilter((x, y) -> Teams.opposing(Teams.Team.A, Teams.Team.B));
        assertEquals(1, opposite.update(touching, holders, 1000, 5, true).size());
    }

    private static ContactTracker tracker()
    {
        return new ContactTracker((x, y) -> TileRef.NONE, () -> 330);
    }

}
