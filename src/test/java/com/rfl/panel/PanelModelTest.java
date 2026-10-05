package com.rfl.panel;

import com.rfl.Fixtures;
import com.rfl.contact.Collision;
import com.rfl.incomplete.Incomplete;
import com.rfl.replay.ReplayState;
import com.rfl.replay.ReplayStatus;

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
    /** Long enough that a collision's end time (19:42:11) differs from its start. */
    private static final long COLLISION_MS = 1200;

    private static Collision collision(String a, String b, List<String> ball, long startMs, int overlap)
    {
        return Fixtures.collision(a, b).holders(ball.toArray(new String[0])).at(startMs, COLLISION_MS)
            .triangles(overlap).build();
    }

    private static Incomplete incomplete(String receiver, List<String> contacts, long timeMs)
    {
        return Fixtures.incomplete(receiver).contacts(contacts.toArray(new String[0])).at(timeMs, 0).build();
    }

    private static PanelModel model(List<Collision> collisions, List<Incomplete> incompletes,
        Object latest)
    {
        return PanelModel.of(true, true, collisions.size(), incompletes.size(), collisions, incompletes, latest, null, null, UTC);
    }

    @Test
    public void timesAreLocalHms()
    {
        assertEquals("19:42:10", PanelModel.time(T, UTC));
        assertEquals("21:42:10", PanelModel.time(T, ZoneOffset.ofHours(2)));
    }

    @Test
    public void theStatusSaysWhetherYouAreInAHouse()
    {
        assertEquals("In a house", model(List.of(), List.of(), null).status());
        PanelModel out = PanelModel.of(false, false, 0, 0, List.of(), List.of(), null, null, null, UTC);
        assertEquals("Not in a house", out.status());
        assertFalse(out.isRecording());
    }

    @Test
    public void countsComeFromTheSessionNotTheRowCap()
    {
        PanelModel m = PanelModel.of(true, false, 73, 4, List.of(), List.of(), null, null, null, UTC);
        assertEquals(73, m.getCollisionCount());
        assertEquals(4, m.getIncompleteCount());
    }

    @Test
    public void latestIncompleteText()
    {
        Incomplete i = incomplete("Bob", List.of("Amy"), T);
        PanelModel m = model(List.of(), List.of(i), i);
        assertEquals(PanelModel.Kind.INCOMPLETE, m.getLatest().getKind());
        assertEquals("<b>Bob caught it in contact with Amy</b>", m.getLatest().getBodyHtml());
        assertEquals("19:42:10", m.getLatest().getTime());
    }

    @Test
    public void latestCollisionTextUsesEndTimeAndHolder()
    {
        Collision c = collision("Amy", "Bob", List.of("Bob"), T, 12);
        PanelModel.LatestEvent e = PanelModel.latestEvent(c, UTC);
        assertEquals(PanelModel.Kind.COLLISION, e.getKind());
        assertEquals("Amy ↔ <b><u>Bob</u></b>, Bob had the ball", e.getBodyHtml());
        assertEquals("the end time", "19:42:11", e.getTime());
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
        assertEquals("Amy ↔ <b><u>Bob</u></b>", PanelModel.pairHtml(row));
        assertEquals("Bob had the ball", PanelModel.ball(row));

        PanelModel.CollisionRow both = PanelModel.collisionRow(
            collision("Amy", "Bob", List.of("Amy", "Bob"), T, 3), UTC);
        assertEquals("both held it: plain", "Amy ↔ Bob", PanelModel.pairHtml(both));
        assertEquals("both had the ball", PanelModel.ball(both));
        assertEquals("no ball", PanelModel.ball(
            PanelModel.collisionRow(collision("Amy", "Bob", List.of(), T, 3), UTC)));
    }

    @Test
    public void pairHtmlEscapesNames()
    {
        PanelModel.CollisionRow row = PanelModel.collisionRow(collision("A<b>", "B&C", List.of("A<b>"), T, 1), UTC);
        assertEquals("<b><u>A&lt;b&gt;</u></b> ↔ B&amp;C", PanelModel.pairHtml(row));
        PanelModel.CollisionRow quote = PanelModel.collisionRow(collision("O'Neil \"Q\"", "Bob", List.of(), T, 1), UTC);
        assertEquals("nobody held it: plain, escaped", "O&#39;Neil &quot;Q&quot; ↔ Bob", PanelModel.pairHtml(quote));
    }

    @Test
    public void latestCollisionHtmlMarksTheLoneHolder()
    {
        PanelModel.LatestEvent e = PanelModel.latestEvent(collision("Amy", "B<o>b", List.of("B<o>b"), T, 12), UTC);
        assertEquals("Amy ↔ <b><u>B&lt;o&gt;b</u></b>, B&lt;o&gt;b had the ball", e.getBodyHtml());
        assertEquals("Amy ↔ Bob, no ball",
            PanelModel.latestEvent(collision("Amy", "Bob", List.of(), T, 12), UTC).getBodyHtml());
    }

    @Test
    public void anIncompleteRowNamesTheReceiverAndContacts()
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
        PanelModel m = model(collisions, List.of(), collisions.get(0));
        assertEquals("C", m.getCollisions().get(0).getA());
        assertEquals("A", m.getCollisions().get(1).getA());
    }

    @Test
    public void equalInputsGiveEqualModels()
    {
        Collision c = collision("Amy", "Bob", List.of("Bob"), T, 12);
        PanelModel a = model(List.of(c), List.of(), c);
        PanelModel b = model(List.of(c), List.of(), c);
        assertEquals(a, b);
        assertNotEquals(a, model(List.of(c), List.of(), null));
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
    public void recordingSplitsIntoTheCardsFixedRows()
    {
        ReplayStatus s = new ReplayStatus(ReplayState.RECORDING, FILE, 151_000L, SIZE, 132, 0.0,
            null, 0L);
        PanelModel.ReplayStrip strip = PanelModel.replayStrip(s, true, T);
        assertEquals(ReplayState.RECORDING, strip.getState());
        assertEquals("Recording", strip.getTitle());
        assertEquals("2:31", strip.getValue());
        assertEquals(FILE, strip.getDetail());
        assertEquals("1.4 MB", strip.getSize());
        assertEquals("132 models", strip.getModels());
        assertEquals("Recording 2:31, 1.4 MB, 132 models", strip.getText());
        assertEquals("1 model", PanelModel.replayStrip(
            new ReplayStatus(ReplayState.RECORDING, FILE, 5_400L, 12_800L, 1, 0.0, null, 0L), true, T)
            .getModels());
        assertEquals("1:02:31", PanelModel.elapsed(3_751_000L));
        assertEquals("900 B", PanelModel.size(900));
    }

    @Test
    public void savingShowsPercent()
    {
        ReplayStatus s = new ReplayStatus(ReplayState.SAVING, FILE, 0L, SIZE, 132, 0.427, null,
            0L);
        PanelModel.ReplayStrip strip = PanelModel.replayStrip(s, false, T);
        assertEquals(ReplayState.SAVING, strip.getState());
        assertEquals("Saving replay", strip.getTitle());
        assertEquals("42%", strip.getValue());
        assertEquals(42, strip.getPercent());
        assertEquals(FILE, strip.getDetail());
        // Never past 100, never below 0.
        assertEquals(100, PanelModel.replayStrip(new ReplayStatus(ReplayState.SAVING, FILE, 0L, SIZE, 0,
            1.7, null, 0L), false, T).getPercent());
        assertEquals(0, PanelModel.replayStrip(new ReplayStatus(ReplayState.SAVING, FILE, 0L, SIZE, 0,
            -1.0, null, 0L), false, T).getPercent());
    }

    @Test
    public void savedShowsFileAndSizeFor10sThenTheIdleCard()
    {
        ReplayStatus s = new ReplayStatus(ReplayState.SAVED, FILE, 0L, SIZE, 132, 1.0, null, T);
        PanelModel.ReplayStrip strip = PanelModel.replayStrip(s, false, T + 9_999);
        assertEquals(ReplayState.SAVED, strip.getState());
        assertEquals("Saved", strip.getTitle());
        assertEquals("1.4 MB", strip.getValue());
        assertEquals(FILE, strip.getDetail());
        assertEquals("Saved " + FILE + " (1.4 MB)", strip.getText());
        assertEquals(100, strip.getPercent());
        assertEquals("idle after 10 s", ReplayState.IDLE, PanelModel.replayStrip(s, false, T + 10_000).getState());
    }

    @Test
    public void idleCardSaysWhetherRecordingIsArmed()
    {
        PanelModel.ReplayStrip off = PanelModel.replayStrip(ReplayStatus.IDLE, false, T);
        assertEquals(ReplayState.IDLE, off.getState());
        assertEquals("Not recording", off.getTitle());
        assertEquals("", off.getValue());
        PanelModel.ReplayStrip armed = PanelModel.replayStrip(ReplayStatus.IDLE, true, T);
        assertEquals("Waiting for a house", armed.getTitle());
        assertEquals("Recording starts when you enter one", armed.getDetail());
        assertEquals(off, PanelModel.replayStrip(null, false, T));
    }

    @Test
    public void errorShowsReason()
    {
        ReplayStatus s = new ReplayStatus(ReplayState.ERROR, FILE, 0L, 0L, 0, 0.0, "disk full",
            0L);
        PanelModel.ReplayStrip strip = PanelModel.replayStrip(s, true, T);
        assertEquals(ReplayState.ERROR, strip.getState());
        assertEquals("Couldn't save replay", strip.getTitle());
        assertEquals("disk full", strip.getDetail());
        assertEquals("Couldn't save replay: disk full", strip.getText());
        assertEquals("Couldn't save replay: unknown error", PanelModel.replayStrip(
            new ReplayStatus(ReplayState.ERROR, FILE, 0L, 0L, 0, 0.0, null, 0L), true, T).getText());
        // Stays up until the next recording: an error must not vanish on its own.
        assertEquals(strip, PanelModel.replayStrip(s, true, T + 3_600_000L));
    }

    @Test
    public void everyCardStateFillsTheSameRows()
    {
        // The card has fixed rows: every state gives every row a value (blank is ""), never null.
        for (ReplayState state : ReplayState.values())
        {
            PanelModel.ReplayStrip strip = PanelModel.replayStrip(new ReplayStatus(state, FILE, 1_000L, SIZE,
                3, 0.5, "x", T), true, T);
            assertTrue(state.name(), strip.getTitle() != null && strip.getValue() != null
                && strip.getDetail() != null && strip.getSize() != null && strip.getModels() != null
                && strip.getText() != null);
        }
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
        PanelModel.ReplayStrip strip = PanelModel.replayStrip(new ReplayStatus(ReplayState.SAVING, FILE,
            0L, SIZE, 1, 0.5, null, 0L), false, T);
        PanelModel a = PanelModel.of(true, false, 0, 0, List.of(), List.of(), null, strip, null, UTC);
        PanelModel b = PanelModel.of(true, false, 0, 0, List.of(), List.of(), null, strip, null, UTC);
        assertEquals(a, b);
        assertNotEquals(a, PanelModel.of(true, false, 0, 0, List.of(), List.of(), null, null, null, UTC));
        assertNotEquals(a, PanelModel.of(true, false, 0, 0, List.of(), List.of(), null, strip,
            "Saved collision", UTC));
    }

    @Test
    public void clearQuestionSaysTheFilesAreKept()
    {
        assertEquals("Clear 12 collisions from this panel? Only the list and count here are reset; "
            + "the saved files in rfl/collisions are kept.", PanelModel.clearQuestion(12, "collisions"));
        assertTrue(PanelModel.clearQuestion(1, "incompletes").startsWith("Clear 1 incomplete from"));
    }
}
