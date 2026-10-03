package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.Test;

/** {@link PanelModel}: what the RFL panel shows, without Swing. */
public class PanelModelTest
{
    private static final ZoneId UTC = ZoneOffset.UTC;
    /** 2026-09-21 19:42:10 UTC. */
    private static final long T = 1_790_019_730_000L;

    private static Collision collision(String a, String b, List<String> ball, long startMs, int overlap)
    {
        return new Collision(a, b, ball, startMs, startMs + 1200, 10, 12, 330, 1, 2, 0, overlap);
    }

    private static CollisionLog.Interception interception(String receiver, List<String> contacts, long timeMs)
    {
        return new CollisionLog.Interception(receiver, contacts, timeMs, 20, 330, 1, 2, 0);
    }

    private static PanelModel model(List<PluginEntry> plugins, List<Collision> collisions,
        List<CollisionLog.Interception> interceptions, Object latest, List<PluginLog.Toggle> toggles, String debug)
    {
        return PanelModel.of(true, true, plugins, collisions.size(), interceptions.size(), collisions, interceptions,
            latest, toggles, debug, UTC);
    }

    @Test
    public void timesAreLocalHms()
    {
        assertEquals("19:42:10", PanelModel.time(T, UTC));
        assertEquals("21:42:10", PanelModel.time(T, ZoneOffset.ofHours(2)));
    }

    @Test
    public void statusText()
    {
        assertEquals("In a house", model(List.of(), List.of(), List.of(), null, List.of(), null).status());
        PanelModel out = PanelModel.of(false, false, List.of(), 0, 0, List.of(), List.of(), null, List.of(), null, UTC);
        assertEquals("Not in a house", out.status());
        assertFalse(out.isRecording());
    }

    @Test
    public void countsComeFromTheSessionNotTheRowCap()
    {
        PanelModel m = PanelModel.of(true, false, List.of(), 73, 4, List.of(), List.of(), null, List.of(), null, UTC);
        assertEquals(73, m.getCollisionCount());
        assertEquals(4, m.getInterceptionCount());
    }

    @Test
    public void latestInterceptionText()
    {
        CollisionLog.Interception i = interception("Bob", List.of("Amy"), T);
        PanelModel m = model(List.of(), List.of(), List.of(i), i, List.of(), null);
        assertEquals(PanelModel.Kind.INTERCEPTION, m.getLatest().getKind());
        assertEquals("Bob caught it in contact with Amy", m.getLatest().getBody());
        assertEquals("Interception: Bob caught it in contact with Amy, 19:42:10", m.getLatest().text());
    }

    @Test
    public void latestCollisionTextUsesEndTimeAndHolder()
    {
        Collision c = collision("Amy", "Bob", List.of("Bob"), T, 12);
        PanelModel.LatestEvent e = PanelModel.latestEvent(c, UTC);
        assertEquals(PanelModel.Kind.COLLISION, e.getKind());
        assertEquals("Collision: Amy ↔ Bob, Bob had the ball, 19:42:11", e.text());
        assertNull(PanelModel.latestEvent(null, UTC));
    }

    @Test
    public void collisionRowMarksTheHolder()
    {
        PanelModel.CollisionRow row = PanelModel.collisionRow(collision("Amy", "Bob", List.of("Bob"), T, 12), UTC);
        assertEquals("19:42:10", row.getTime());
        assertEquals("Amy ↔ Bob", row.pair());
        assertFalse(row.isAHasBall());
        assertTrue(row.isBHasBall());
        assertEquals(12, row.getOverlap());
        assertEquals("Amy ↔ <b>Bob</b>", PanelModel.pairHtml(row));
        assertEquals("Bob had the ball", PanelModel.ball(row));

        PanelModel.CollisionRow both = PanelModel.collisionRow(
            collision("Amy", "Bob", List.of("Amy", "Bob"), T, 3), UTC);
        assertEquals("<b>Amy</b> ↔ <b>Bob</b>", PanelModel.pairHtml(both));
        assertEquals("both had the ball", PanelModel.ball(both));
        assertEquals("no ball", PanelModel.ball(
            PanelModel.collisionRow(collision("Amy", "Bob", List.of(), T, 3), UTC)));
    }

    @Test
    public void pairHtmlEscapesNames()
    {
        PanelModel.CollisionRow row = PanelModel.collisionRow(collision("A<b>", "B&C", List.of("A<b>"), T, 1), UTC);
        assertEquals("<b>A&lt;b&gt;</b> ↔ B&amp;C", PanelModel.pairHtml(row));
    }

    @Test
    public void interceptionRow()
    {
        PanelModel.InterceptionRow row = PanelModel.interceptionRow(interception("Bob", List.of("Amy", "Cy"), T), UTC);
        assertEquals("19:42:10", row.getTime());
        assertEquals("Bob", row.getReceiver());
        assertEquals("in contact with Amy, Cy", row.getContacts());
        assertEquals("no contact recorded", PanelModel.contacts(List.of()));
    }

    @Test
    public void rowsKeepNewestFirstOrder()
    {
        List<Collision> collisions = List.of(collision("C", "D", List.of("C"), T + 5000, 2),
            collision("A", "B", List.of("A"), T, 1));
        PanelModel m = model(List.of(), collisions, List.of(), collisions.get(0), List.of(), null);
        assertEquals("C", m.getCollisions().get(0).getA());
        assertEquals("A", m.getCollisions().get(1).getA());
    }

    @Test
    public void bannedAlertText()
    {
        assertNull(PanelModel.bannedAlert(List.of()));
        assertEquals("Banned plugin enabled: Block Tracker", PanelModel.bannedAlert(List.of("Block Tracker")));
        assertEquals("Banned plugins enabled: Block Tracker, True Tile Player Indicators",
            PanelModel.bannedAlert(List.of("Block Tracker", "True Tile Player Indicators")));

        PanelModel m = model(List.of(new PluginEntry("True Tile Player Indicators", true, PluginEntry.HUB),
            new PluginEntry("Block Tracker", false, PluginEntry.HUB)), List.of(), List.of(), null, List.of(), null);
        assertEquals("Banned plugin enabled: True Tile Player Indicators", m.bannedAlert());
    }

    @Test
    public void toggleRows()
    {
        PanelModel m = model(List.of(), List.of(), List.of(), null,
            List.of(new PluginLog.Toggle(T, "Ref", 330, "Block Tracker", true)), null);
        assertEquals(List.of(new PanelModel.ToggleRow("19:42:10", "Block Tracker", true)), m.getToggles());
    }

    @Test
    public void debugTextHidesTheTabWhenNull()
    {
        assertNull(model(List.of(), List.of(), List.of(), null, List.of(), null).getDebugText());
        assertEquals("GATE", model(List.of(), List.of(), List.of(), null, List.of(), "GATE").getDebugText());
    }

    @Test
    public void equalInputsGiveEqualModels()
    {
        Collision c = collision("Amy", "Bob", List.of("Bob"), T, 12);
        PanelModel a = model(List.of(), List.of(c), List.of(), c, List.of(), null);
        PanelModel b = model(List.of(), List.of(c), List.of(), c, List.of(), null);
        assertEquals(a, b);
        assertNotEquals(a, model(List.of(), List.of(c), List.of(), c, List.of(), "x"));
    }

    @Test
    public void copyResultMessages()
    {
        assertEquals("No plugin history yet today", PanelModel.copyResult(0));
        assertEquals("Copied 1 line", PanelModel.copyResult(1));
        assertEquals("Copied 12 lines", PanelModel.copyResult(12));
        assertEquals(0, PanelModel.lineCount(null));
        assertEquals(0, PanelModel.lineCount(""));
        assertEquals(2, PanelModel.lineCount("{\"a\":1}\n{\"b\":2}\n"));
        assertEquals(2, PanelModel.lineCount("{\"a\":1}\r\n\r\n{\"b\":2}"));
    }
}
