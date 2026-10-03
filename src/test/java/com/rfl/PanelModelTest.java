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
        return new Collision(a, b, ball, startMs, startMs + 1200, 10, 12, 330, 1, 2, 0, 0, 0, overlap);
    }

    private static CollisionLog.Incomplete incomplete(String receiver, List<String> contacts, long timeMs)
    {
        return new CollisionLog.Incomplete(receiver, contacts, timeMs, 20, 330, 1, 2, 0, 0, 0);
    }

    private static PanelModel model(List<Collision> collisions, List<CollisionLog.Incomplete> incompletes,
        Object latest, String debug)
    {
        return PanelModel.of(true, true, collisions.size(), incompletes.size(), collisions, incompletes, latest,
            debug, UTC);
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
        assertEquals("In a house", model(List.of(), List.of(), null, null).status());
        PanelModel out = PanelModel.of(false, false, 0, 0, List.of(), List.of(), null, null, UTC);
        assertEquals("Not in a house", out.status());
        assertFalse(out.isRecording());
    }

    @Test
    public void countsComeFromTheSessionNotTheRowCap()
    {
        PanelModel m = PanelModel.of(true, false, 73, 4, List.of(), List.of(), null, null, UTC);
        assertEquals(73, m.getCollisionCount());
        assertEquals(4, m.getIncompleteCount());
    }

    @Test
    public void latestIncompleteText()
    {
        CollisionLog.Incomplete i = incomplete("Bob", List.of("Amy"), T);
        PanelModel m = model(List.of(), List.of(i), i, null);
        assertEquals(PanelModel.Kind.INCOMPLETE, m.getLatest().getKind());
        assertEquals("Bob caught it in contact with Amy", m.getLatest().getBody());
        assertEquals("Incomplete: Bob caught it in contact with Amy, 19:42:10", m.getLatest().text());
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
    public void incompleteRow()
    {
        PanelModel.IncompleteRow row = PanelModel.incompleteRow(incomplete("Bob", List.of("Amy", "Cy"), T), UTC);
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
        PanelModel m = model(collisions, List.of(), collisions.get(0), null);
        assertEquals("C", m.getCollisions().get(0).getA());
        assertEquals("A", m.getCollisions().get(1).getA());
    }

    @Test
    public void debugTextHidesTheTabWhenNull()
    {
        assertNull(model(List.of(), List.of(), null, null).getDebugText());
        assertEquals("GATE", model(List.of(), List.of(), null, "GATE").getDebugText());
    }

    @Test
    public void equalInputsGiveEqualModels()
    {
        Collision c = collision("Amy", "Bob", List.of("Bob"), T, 12);
        PanelModel a = model(List.of(c), List.of(), c, null);
        PanelModel b = model(List.of(c), List.of(), c, null);
        assertEquals(a, b);
        assertNotEquals(a, model(List.of(c), List.of(), c, "x"));
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

    @Test
    public void recordButtonSaysWhatAClickDoesAndTheTipSaysWhatIsHappening()
    {
        assertEquals("Start recording", PanelModel.recordButtonText(false));
        assertEquals("Stop recording", PanelModel.recordButtonText(true));
        assertEquals("Records each house visit to a replay file in rfl/replays.",
            PanelModel.recordButtonTip(false, false));
        assertEquals("Waiting for a house: recording starts when you enter one.",
            PanelModel.recordButtonTip(true, false));
        assertEquals("Recording. Click to stop and save the replay file.", PanelModel.recordButtonTip(true, true));
    }

    private static final String FILE = "2026-10-03_120000_w330.rflr.gz";
    /** 1.4 MB in the panel's binary megabytes. */
    private static final long SIZE = 1_468_006L;

    @Test
    public void recordingStripText()
    {
        ReplayRecorder.Status s = new ReplayRecorder.Status(ReplayState.RECORDING, FILE, 151_000L, SIZE, 132, 0.0,
            null, 0L);
        PanelModel.ReplayStrip strip = PanelModel.replayStrip(s, T);
        assertEquals(ReplayState.RECORDING, strip.getState());
        assertEquals("Recording 2:31 \u00b7 1.4 MB \u00b7 132 models", strip.getText());
        assertEquals(FILE, strip.getDetail());
        assertEquals("Recording 0:05 \u00b7 12 KB \u00b7 1 model", PanelModel.replayStrip(
            new ReplayRecorder.Status(ReplayState.RECORDING, FILE, 5_400L, 12_800L, 1, 0.0, null, 0L), T).getText());
        assertEquals("1:02:31", PanelModel.elapsed(3_751_000L));
        assertEquals("900 B", PanelModel.size(900));
    }

    @Test
    public void savingShowsPercent()
    {
        ReplayRecorder.Status s = new ReplayRecorder.Status(ReplayState.SAVING, FILE, 0L, SIZE, 132, 0.427, null,
            0L);
        PanelModel.ReplayStrip strip = PanelModel.replayStrip(s, T);
        assertEquals(ReplayState.SAVING, strip.getState());
        assertEquals("Saving replay\u2026 42%", strip.getText());
        assertEquals(42, strip.getPercent());
        assertEquals(FILE, strip.getDetail());
        // Never past 100, never below 0.
        assertEquals(100, PanelModel.replayStrip(new ReplayRecorder.Status(ReplayState.SAVING, FILE, 0L, SIZE, 0,
            1.7, null, 0L), T).getPercent());
        assertEquals(0, PanelModel.replayStrip(new ReplayRecorder.Status(ReplayState.SAVING, FILE, 0L, SIZE, 0,
            -1.0, null, 0L), T).getPercent());
    }

    @Test
    public void savedShowsFileAndSizeFor10s()
    {
        ReplayRecorder.Status s = new ReplayRecorder.Status(ReplayState.SAVED, FILE, 0L, SIZE, 132, 1.0, null, T);
        PanelModel.ReplayStrip strip = PanelModel.replayStrip(s, T + 9_999);
        assertEquals(ReplayState.SAVED, strip.getState());
        assertEquals("Saved " + FILE + " (1.4 MB)", strip.getText());
        assertEquals(100, strip.getPercent());
        assertNull("gone after 10 s", PanelModel.replayStrip(s, T + 10_000));
        assertNull(PanelModel.replayStrip(ReplayRecorder.Status.IDLE, T));
    }

    @Test
    public void errorShowsReason()
    {
        ReplayRecorder.Status s = new ReplayRecorder.Status(ReplayState.ERROR, FILE, 0L, 0L, 0, 0.0, "disk full",
            0L);
        PanelModel.ReplayStrip strip = PanelModel.replayStrip(s, T);
        assertEquals(ReplayState.ERROR, strip.getState());
        assertEquals("Couldn't save replay: disk full", strip.getText());
        assertEquals("Couldn't save replay: unknown error", PanelModel.replayStrip(
            new ReplayRecorder.Status(ReplayState.ERROR, FILE, 0L, 0L, 0, 0.0, null, 0L), T).getText());
        // Stays up until the next recording: an error must not vanish on its own.
        assertEquals(strip, PanelModel.replayStrip(s, T + 3_600_000L));
    }

    @Test
    public void savedTickFlashesForTwoSecondsNewestWins()
    {
        assertNull(PanelModel.savedTick(0L, 0L, T));
        assertEquals("Saved collision", PanelModel.savedTick(T, 0L, T + 1_999));
        assertNull(PanelModel.savedTick(T, 0L, T + 2_000));
        assertEquals("Saved plugin list", PanelModel.savedTick(0L, T, T + 10));
        assertEquals("Saved plugin list", PanelModel.savedTick(T, T + 5, T + 10));
        assertEquals("Saved collision", PanelModel.savedTick(T + 5, T, T + 10));
    }

    @Test
    public void stripAndTickTakePartInEquality()
    {
        PanelModel.ReplayStrip strip = PanelModel.replayStrip(new ReplayRecorder.Status(ReplayState.SAVING, FILE,
            0L, SIZE, 1, 0.5, null, 0L), T);
        PanelModel a = PanelModel.of(true, false, 0, 0, List.of(), List.of(), null, null, strip, null, UTC);
        PanelModel b = PanelModel.of(true, false, 0, 0, List.of(), List.of(), null, null, strip, null, UTC);
        assertEquals(a, b);
        assertNotEquals(a, PanelModel.of(true, false, 0, 0, List.of(), List.of(), null, null, null, null, UTC));
        assertNotEquals(a, PanelModel.of(true, false, 0, 0, List.of(), List.of(), null, null, strip,
            "Saved collision", UTC));
    }
}
