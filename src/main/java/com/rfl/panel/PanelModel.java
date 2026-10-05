package com.rfl.panel;

import sh.yumekui.toolkit.text.PlayerNames;
import sh.yumekui.toolkit.text.Html;
import com.rfl.contact.Collision;
import com.rfl.incomplete.Incomplete;
import com.rfl.replay.ReplayState;
import com.rfl.replay.ReplayStatus;
import com.rfl.teams.Teams;
import sh.yumekui.toolkit.scene.SceneStamps;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

import lombok.Value;

/**
 * Everything the RFL panel shows, as plain values: built on the client thread from the plugin's
 * state ({@link #of}), handed to the EDT, and rendered by {@link RflPanel} without further
 * formatting. Equal models render identically, so an unchanged model is never re-sent.
 * No Swing here; unit-tested on its own.
 */
@Value
public final class PanelModel
{
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    static final String NO_HISTORY = "No plugin history yet today";
    /** How long "Saved <file>" stays up after a replay finishes saving. */
    static final long SAVED_SHOW_MS = 10_000;
    /** How long a "Saved collision" / "Saved plugin list" tick stays up. */
    static final long TICK_SHOW_MS = 2_000;
    /** Units for the card's elapsed time and file size (binary units, as file managers show). */
    private static final long MS_PER_SECOND = 1000;
    private static final long SECONDS_PER_MINUTE = 60;
    private static final long MINUTES_PER_HOUR = 60;
    private static final long SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR;
    private static final long BYTES_PER_KB = 1024;
    private static final long BYTES_PER_MB = BYTES_PER_KB * 1024;
    /** A full saving bar. */
    static final int PERCENT = 100;
    static final String COPY_FAILED = "Couldn't read today's plugin history";
    /** Another program held the clipboard. */
    static final String CLIPBOARD_BUSY = "Clipboard busy, try again";

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
        /**
         * "Bob caught it in contact with Amy" / "Amy ↔ Bob, Bob had the ball", as escaped HTML; a
         * collision's lone ball holder is bold and underlined.
         */
        String bodyHtml;
    }

    /**
     * The replay card under the status line: what the replay file is doing right now, split into
     * the card's fixed rows so the card never changes height. Empty strings leave a row blank.
     */
    @Value
    static class ReplayStrip
    {
        ReplayState state;
        /** Row 1, left: "Recording", "Saving replay", "Saved", "Couldn't save replay", "Not recording". */
        String title;
        /** Row 1, right, fixed width: "2:31", "42%", "1.4 MB", or "". */
        String value;
        /** Row 2: the file name, the error reason, or a hint while idle. */
        String detail;
        /** Row 3 while recording: the size on disk ("1.4 MB"); "" otherwise. */
        String size;
        /** Row 3 while recording: "132 models"; "" otherwise. */
        String models;
        /** 0..100: the saving bar. */
        int percent;
        /** Everything on one line, for the card's tooltip: "Recording 2:31, 1.4 MB, 132 models". */
        String text;
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
        /** Recorded scene tile and its {@link SceneStamps} stamp, for the selected-row highlight. */
        int sx;
        int sy;
        int stamp;

        /** "Amy ↔ Bob", plain. */
        String pair()
        {
            return a + " ↔ " + b;
        }
    }

    /** One row of the Teams view: a player in the house or already assigned, and their team. */
    @Value
    public static class TeamRow
    {
        String name;
        /** Null: unassigned. */
        Teams.Team team;
        /** In the house right now; an absent assigned player is shown dimmed. */
        boolean present;
    }

    /** The Teams view's state. */
    @Value
    public static class TeamsState
    {
        static final TeamsState NONE = new TeamsState(Collections.emptyList(), 0, 0, false);

        /** Alphabetical, ignoring case. */
        List<TeamRow> rows;
        int teamA;
        int teamB;
        /** Detection is on but a team is empty, so nothing can count: show {@link #TEAM_HINT}. */
        boolean hint;
    }

    static final String TEAM_HINT = "Assign players to Team A and B to detect collisions";
    static final String CLEAR_TEAMS_QUESTION = "Clear all team assignments? Everyone becomes unassigned, so "
        + "nothing is detected until you assign teams again.";

    @Value
    static class IncompleteRow
    {
        String time;
        String receiver;
        /** "in contact with X, Y". */
        String contacts;
        /** Recorded scene tile and its {@link SceneStamps} stamp, for the selected-row highlight. */
        int sx;
        int sy;
        int stamp;
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
    /** Null when there is nothing to say about a replay file. */
    ReplayStrip replay;
    /** "Saved collision" / "Saved plugin list" for a moment after a line is written; else null. */
    String savedTick;
    TeamsState teams;

    /**
     * A model with no teams and no scene stamps.
     *
     * @param collisions this session's collisions, newest first
     * @param incompletes this session's incompletes, newest first
     * @param latest the latest {@link Collision} or {@link Incomplete}, or null
     * @param replay {@link #replayStrip}; null only in tests
     * @param savedTick {@link #savedTick}, or null for none
     */
    static PanelModel of(boolean inPoh, boolean recording, int collisionCount, int incompleteCount,
        List<Collision> collisions, List<Incomplete> incompletes, Object latest,
        ReplayStrip replay, String savedTick, ZoneId zone)
    {
        return of(inPoh, recording, collisionCount, incompleteCount, collisions, incompletes, latest, replay, savedTick,
            TeamsState.NONE, e -> SceneStamps.NONE, zone);
    }

    /** @param stampOf each event's scene stamp ({@link SessionEvents#stamp}) */
    static PanelModel of(boolean inPoh, boolean recording, int collisionCount, int incompleteCount,
        List<Collision> collisions, List<Incomplete> incompletes, Object latest,
        ReplayStrip replay, String savedTick, TeamsState teams, ToIntFunction<Object> stampOf, ZoneId zone)
    {
        List<CollisionRow> collisionRows = new ArrayList<>(collisions.size());
        for (Collision c : collisions)
        {
            collisionRows.add(collisionRow(c, zone, stampOf.applyAsInt(c)));
        }
        List<IncompleteRow> incompleteRows = new ArrayList<>(incompletes.size());
        for (Incomplete i : incompletes)
        {
            incompleteRows.add(incompleteRow(i, zone, stampOf.applyAsInt(i)));
        }
        return new PanelModel(inPoh, recording, collisionCount, incompleteCount, latestEvent(latest, zone),
            Collections.unmodifiableList(collisionRows), Collections.unmodifiableList(incompleteRows), replay,
            savedTick, teams);
    }

    /**
     * The Teams view: everyone in the house plus everyone assigned (absent ones flagged), one row per
     * player ({@link PlayerNames#matchKey}), alphabetical. The hint shows when detection is on and a team is empty.
     *
     * @param present sanitized names of the players in the house now
     * @param assigned {@link Teams#assigned}
     */
    public static TeamsState teams(List<String> present, Map<String, Teams.Team> assigned, boolean detecting)
    {
        Map<String, TeamRow> rows = new LinkedHashMap<>();
        Set<String> here = new HashSet<>();
        for (String name : present)
        {
            String key = PlayerNames.matchKey(name);
            if (key != null)
            {
                here.add(key);
            }
        }
        int teamA = 0;
        int teamB = 0;
        for (Map.Entry<String, Teams.Team> entry : assigned.entrySet())
        {
            String key = PlayerNames.matchKey(entry.getKey());
            if (key == null)
            {
                continue;
            }
            rows.put(key, new TeamRow(entry.getKey(), entry.getValue(), here.contains(key)));
            teamA += entry.getValue() == Teams.Team.A ? 1 : 0;
            teamB += entry.getValue() == Teams.Team.B ? 1 : 0;
        }
        for (String name : present)
        {
            String key = PlayerNames.matchKey(name);
            if (key != null && !rows.containsKey(key))
            {
                rows.put(key, new TeamRow(name, null, true));
            }
        }
        List<TeamRow> sorted = new ArrayList<>(rows.values());
        sorted.sort((x, y) -> String.CASE_INSENSITIVE_ORDER.compare(x.getName(), y.getName()));
        return new TeamsState(Collections.unmodifiableList(sorted), teamA, teamB, detecting && (teamA == 0 || teamB == 0));
    }

    /**
     * The replay card for a recorder {@link ReplayStatus} at {@code nowMs}. Never null: the
     * card always holds its place, so starting, saving and finishing a replay move nothing else.
     * Idle (or saved more than {@link #SAVED_SHOW_MS} ago) says whether recording is armed
     * ({@code armed}, the Record replays setting). An error stays until the next recording.
     */
    static ReplayStrip replayStrip(ReplayStatus status, boolean armed, long nowMs)
    {
        ReplayState state = status == null ? ReplayState.IDLE : status.getState();
        String file = status == null || status.getFileName() == null ? "" : status.getFileName();
        switch (state)
        {
            case RECORDING:
            {
                String time = elapsed(status.getElapsedMs());
                String size = size(status.getBytes());
                String models = status.getModels() + (status.getModels() == 1 ? " model" : " models");
                return new ReplayStrip(state, "Recording", time, file, size, models, 0,
                    "Recording " + time + ", " + size + ", " + models);
            }
            case SAVING:
            {
                int percent = (int) Math.floor(Math.max(0.0, Math.min(1.0, status.getProgress())) * PERCENT);
                return new ReplayStrip(state, "Saving replay", percent + "%", file, "", "", percent,
                    "Saving replay " + percent + "%: " + file);
            }
            case SAVED:
                if (nowMs - status.getSavedAtMs() < SAVED_SHOW_MS)
                {
                    String size = size(status.getBytes());
                    return new ReplayStrip(state, "Saved", size, file, "", "", PERCENT,
                        "Saved " + file + " (" + size + ")");
                }
                break;
            case ERROR:
            {
                String reason = status.getError() == null || status.getError().isEmpty() ? "unknown error" : status.getError();
                return new ReplayStrip(state, "Couldn't save replay", "", reason, "", "", 0,
                    "Couldn't save replay: " + reason);
            }
            default:
                break;
        }
        String title = armed ? "Waiting for a house" : "Not recording";
        String detail = armed ? "Recording starts when you enter one" : "Start recording to save house visits";
        return new ReplayStrip(ReplayState.IDLE, title, "", detail, "", "", 0, title + ": " + detail);
    }

    /** Widest text the card's fixed-width value may hold, for sizing it once: hours of recording. */
    static final String VALUE_SAMPLE = "00:00:00";
    /** Widest size text: up to "1023.9 MB". */
    static final String SIZE_SAMPLE = "0000.0 MB";
    /** Widest models text. */
    static final String MODELS_SAMPLE = "00000 models";

    /** "Saved collision" or "Saved plugin list" (the newer) within {@link #TICK_SHOW_MS}; else null. */
    static String savedTick(long collisionSavedAtMs, long pluginSavedAtMs, long nowMs)
    {
        boolean collision = collisionSavedAtMs > 0 && nowMs - collisionSavedAtMs < TICK_SHOW_MS;
        boolean plugin = pluginSavedAtMs > 0 && nowMs - pluginSavedAtMs < TICK_SHOW_MS;
        if (collision && (!plugin || collisionSavedAtMs > pluginSavedAtMs))
        {
            return "Saved collision";
        }
        return plugin ? "Saved plugin list" : null;
    }

    /** "2:31", or "1:02:31" from an hour. */
    static String elapsed(long ms)
    {
        long total = Math.max(0L, ms) / MS_PER_SECOND;
        long hours = total / SECONDS_PER_HOUR;
        long minutes = total / SECONDS_PER_MINUTE % MINUTES_PER_HOUR;
        long seconds = total % SECONDS_PER_MINUTE;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
            : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    /** "900 B", "12 KB", "1.4 MB" (binary units). */
    static String size(long bytes)
    {
        if (bytes < BYTES_PER_KB)
        {
            return Math.max(0L, bytes) + " B";
        }
        if (bytes < BYTES_PER_MB)
        {
            return bytes / BYTES_PER_KB + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (double) BYTES_PER_MB);
    }

    /** The copy button's label. */
    static final String COPY_BUTTON = "Copy plugin history";

    /**
     * The inline confirmation for Clear on a list: what goes and, as plainly, what stays.
     *
     * @param plural "collisions" or "incompletes"
     */
    static String clearQuestion(int count, String plural)
    {
        String noun = count == 1 && plural.endsWith("s") ? plural.substring(0, plural.length() - 1) : plural;
        return "Clear " + count + " " + noun + " from this panel? Only the list and count here are reset; "
            + "the saved files in rfl/collisions are kept.";
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

    static CollisionRow collisionRow(Collision collision, ZoneId zone)
    {
        return collisionRow(collision, zone, SceneStamps.NONE);
    }

    static CollisionRow collisionRow(Collision collision, ZoneId zone, int stamp)
    {
        return new CollisionRow(time(collision.startMs, zone), collision.a, collision.b, collision.ball.contains(collision.a), collision.ball.contains(collision.b),
            collision.maxTriangles, collision.sx, collision.sy, stamp);
    }

    static IncompleteRow incompleteRow(Incomplete incomplete, ZoneId zone)
    {
        return incompleteRow(incomplete, zone, SceneStamps.NONE);
    }

    static IncompleteRow incompleteRow(Incomplete incomplete, ZoneId zone, int stamp)
    {
        return new IncompleteRow(time(incomplete.timeMs, zone), incomplete.receiver, contacts(incomplete.contacts), incomplete.sx, incomplete.sy, stamp);
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
        if (event instanceof Incomplete)
        {
            Incomplete incomplete = (Incomplete) event;
            String body = incomplete.receiver + " caught it"
                + (incomplete.contacts == null || incomplete.contacts.isEmpty() ? "" : " " + contacts(incomplete.contacts));
            return new LatestEvent(Kind.INCOMPLETE, time(incomplete.timeMs, zone), "<b>" + Html.escape(body) + "</b>");
        }
        if (event instanceof Collision)
        {
            Collision collision = (Collision) event;
            CollisionRow row = collisionRow(collision, zone);
            return new LatestEvent(Kind.COLLISION, time(collision.endMs, zone), pairHtml(row) + ", " + Html.escape(ball(row)));
        }
        return null;
    }

    /** The collision pair as HTML, a lone ball holder bold and underlined: "Amy ↔ <b><u>Bob</u></b>". */
    static String pairHtml(CollisionRow row)
    {
        // Only a lone holder is marked: both or neither holding leaves both names plain.
        boolean one = row.aHasBall != row.bHasBall;
        return name(row.a, one && row.aHasBall) + " ↔ " + name(row.b, one && row.bHasBall);
    }

    private static String name(String name, boolean holder)
    {
        return holder ? "<b><u>" + Html.escape(name) + "</u></b>" : Html.escape(name);
    }
}
