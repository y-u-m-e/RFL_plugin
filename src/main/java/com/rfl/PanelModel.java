package com.rfl;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lombok.Value;

/**
 * Everything the RFL panel shows, as plain values: built on the client thread from the plugin's
 * state ({@link #of}), handed to the EDT, and rendered by {@link RflPanel} without further
 * formatting. Equal models render identically, so an unchanged model is never re-sent.
 * No Swing here; unit-tested on its own.
 */
@Value
final class PanelModel
{
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    static final String NO_HISTORY = "No plugin history yet today";
    static final String COPY_FAILED = "Couldn't read today's plugin history";

    enum Kind
    {
        COLLISION("Collision"),
        INCOMPLETE("Incomplete");

        final String label;

        Kind(String label)
        {
            this.label = label;
        }
    }

    /** The latest collision or incomplete, for the hero card. */
    @Value
    static class LatestEvent
    {
        Kind kind;
        String time;
        /** "Bob caught it in contact with Amy" / "Amy ↔ Bob, Bob had the ball". */
        String body;

        /** One line: "Incomplete: Bob caught it in contact with Amy, 19:42:10". */
        String text()
        {
            return kind.label + ": " + body + ", " + time;
        }
    }

    @Value
    static class CollisionRow
    {
        String time;
        String a;
        String b;
        boolean aHasBall;
        boolean bHasBall;
        int overlap;

        /** "Amy ↔ Bob", plain. */
        String pair()
        {
            return a + " ↔ " + b;
        }
    }

    @Value
    static class IncompleteRow
    {
        String time;
        String receiver;
        /** "in contact with X, Y". */
        String contacts;
    }

    boolean inPoh;
    boolean recording;
    int collisionCount;
    int incompleteCount;
    /** Null before the first event this session. */
    LatestEvent latest;
    /** Newest first. */
    List<CollisionRow> collisions;
    /** Newest first. */
    List<IncompleteRow> incompletes;
    /** The Debug tab's text; null when Debug logging is off, which hides the tab. */
    String debugText;

    /**
     * @param collisions this session's collisions, newest first
     * @param incompletes this session's incompletes, newest first
     * @param latest the latest {@link Collision} or {@link CollisionLog.Incomplete}, or null
     * @param debugText null to hide the Debug tab
     */
    static PanelModel of(boolean inPoh, boolean recording, int collisionCount, int incompleteCount,
        List<Collision> collisions, List<CollisionLog.Incomplete> incompletes, Object latest, String debugText,
        ZoneId zone)
    {
        List<CollisionRow> collisionRows = new ArrayList<>(collisions.size());
        for (Collision c : collisions)
        {
            collisionRows.add(collisionRow(c, zone));
        }
        List<IncompleteRow> incompleteRows = new ArrayList<>(incompletes.size());
        for (CollisionLog.Incomplete i : incompletes)
        {
            incompleteRows.add(incompleteRow(i, zone));
        }
        return new PanelModel(inPoh, recording, collisionCount, incompleteCount, latestEvent(latest, zone),
            Collections.unmodifiableList(collisionRows), Collections.unmodifiableList(incompleteRows), debugText);
    }

    /** The record button's label: what a click does. {@code armed} is the Record replays setting. */
    static String recordButtonText(boolean armed)
    {
        return armed ? "Stop recording" : "Start recording";
    }

    /** The record button's tooltip: what is happening now. */
    static String recordButtonTip(boolean armed, boolean recording)
    {
        if (!armed)
        {
            return "Records each house visit to a replay file in rfl/replays.";
        }
        return recording ? "Recording. Click to stop and save the replay file."
            : "Waiting for a house: recording starts when you enter one.";
    }

    /** Status strip text. */
    String status()
    {
        return inPoh ? "In a house" : "Not in a house";
    }

    static String time(long epochMs, ZoneId zone)
    {
        return TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone));
    }

    static CollisionRow collisionRow(Collision c, ZoneId zone)
    {
        return new CollisionRow(time(c.startMs, zone), c.a, c.b, c.ball.contains(c.a), c.ball.contains(c.b),
            c.maxTriangles);
    }

    static IncompleteRow incompleteRow(CollisionLog.Incomplete i, ZoneId zone)
    {
        return new IncompleteRow(time(i.timeMs, zone), i.receiver, contacts(i.contacts));
    }

    /** "in contact with X, Y", or "no contact recorded". */
    static String contacts(List<String> contacts)
    {
        return contacts == null || contacts.isEmpty() ? "no contact recorded"
            : "in contact with " + String.join(", ", contacts);
    }

    /** "Bob had the ball", "both had the ball" or "no ball". */
    static String ball(CollisionRow row)
    {
        if (row.aHasBall && row.bHasBall)
        {
            return "both had the ball";
        }
        if (row.aHasBall || row.bHasBall)
        {
            return (row.aHasBall ? row.a : row.b) + " had the ball";
        }
        return "no ball";
    }

    static LatestEvent latestEvent(Object event, ZoneId zone)
    {
        if (event instanceof CollisionLog.Incomplete)
        {
            CollisionLog.Incomplete i = (CollisionLog.Incomplete) event;
            String body = i.receiver + " caught it"
                + (i.contacts == null || i.contacts.isEmpty() ? "" : " " + contacts(i.contacts));
            return new LatestEvent(Kind.INCOMPLETE, time(i.timeMs, zone), body);
        }
        if (event instanceof Collision)
        {
            Collision c = (Collision) event;
            CollisionRow row = collisionRow(c, zone);
            return new LatestEvent(Kind.COLLISION, time(c.endMs, zone), row.pair() + ", " + ball(row));
        }
        return null;
    }

    /** Non-empty lines in a day file's text; 0 for null. */
    static int lineCount(String text)
    {
        if (text == null)
        {
            return 0;
        }
        int lines = 0;
        for (String line : text.split("\n"))
        {
            if (!line.trim().isEmpty())
            {
                lines++;
            }
        }
        return lines;
    }

    /** What the panel says after Copy plugin history, given the lines copied (0 = nothing to copy). */
    static String copyResult(int lines)
    {
        if (lines <= 0)
        {
            return NO_HISTORY;
        }
        return "Copied " + lines + (lines == 1 ? " line" : " lines");
    }

    /** Escapes a plain string for a Swing HTML label. */
    static String html(String s)
    {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** The collision pair as HTML, the ball holder's name in bold: "Amy ↔ <b>Bob</b>". */
    static String pairHtml(CollisionRow row)
    {
        return name(row.a, row.aHasBall) + " ↔ " + name(row.b, row.bHasBall);
    }

    private static String name(String name, boolean bold)
    {
        return bold ? "<b>" + html(name) + "</b>" : html(name);
    }
}
