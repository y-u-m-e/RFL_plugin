package com.rfl.log;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import lombok.Value;

/**
 * Copy plugin history's clipboard text: today's plugin log ({@link PluginLog} day file lines)
 * laid out for a league ref pasting it into Discord. A header (player, world, time range), then
 * one block per house visit, newest first: enter to leave time, the plugins on at entry as one
 * alphabetical line, and each plugin turned on ({@code +Name}) or off ({@code -Name}) during the
 * visit, in time order with its time. Wrapped in a code block so Discord shows it as written.
 * Kept within {@link #DISCORD_LIMIT} when it can be: older visits that don't fit are dropped and
 * counted on a "… N more visits" line. The newest visit is always kept whole.
 *
 * <p>No Swing, no IO; unit-tested on its own.
 */
public final class PluginHistory
{
    /** Discord's message length limit for a normal account. */
    public static final int DISCORD_LIMIT = 2000;
    private static final String FENCE = "```";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** What was built: the text, how many visits today's file holds, and how many made it in. */
    @Value
    public static class Result
    {
        String text;
        int visits;
        int shown;
    }

    /** One plugin turned on or off. */
    private static final class Toggle
    {
        final long timeMs;
        final String name;
        final boolean enabled;

        Toggle(long timeMs, String name, boolean enabled)
        {
            this.timeMs = timeMs;
            this.name = name;
            this.enabled = enabled;
        }
    }

    /** One house visit: enter to leave (either may be unknown), the plugin list, the toggles in it. */
    private static final class Visit
    {
        /** -1 when the visit began before today's file (only a leave snapshot was seen). */
        long startMs = -1;
        /** -1 while still in the house, or when the leave was never written. */
        long endMs = -1;
        /** Still in the house: the last visit, never left. */
        boolean ongoing;
        int world;
        /** Names of plugins on, alphabetical; sideloaded ones end in "*". */
        List<String> on = Collections.emptyList();
        final List<Toggle> toggles = new ArrayList<>();
    }

    private PluginHistory()
    {
    }

    /** Today's file read into house visits, with what the header needs. */
    private static final class Day
    {
        final List<Visit> visits = new ArrayList<>();
        /** Every RSN seen, in first-seen order (a player may change accounts in a day). */
        final Set<String> players = new LinkedHashSet<>();
        final Set<Integer> worlds = new TreeSet<>();
        /** The visit an enter snapshot opened and no leave has closed yet. */
        Visit open;
        /** Toggles while not in a house; counted, not listed. */
        int outside;
        /** Epoch ms of the first and last line counted; -1 before any. */
        long first = -1;
        long last = -1;
        boolean sideloaded;
    }

    /**
     * @param lines today's day-file lines; torn or foreign lines are skipped
     * @param limit the length to stay within when possible ({@link #DISCORD_LIMIT})
     * @return the text, or a result with null text when the lines hold no snapshot or toggle
     */
    public static Result format(Gson gson, List<String> lines, ZoneId zone, int limit)
    {
        Day day = read(gson, lines);
        if (day.first < 0)
        {
            return new Result(null, 0, 0);
        }
        String tail = day.outside == 0 ? ""
            : "\n" + day.outside + (day.outside == 1 ? " plugin toggle" : " plugin toggles")
                + " outside a house (full list in rfl/plugins)\n";
        return fit(header(day, zone), blocks(day.visits, zone), tail, limit, day.visits.size());
    }

    private static Day read(Gson gson, List<String> lines)
    {
        Day day = new Day();
        for (String text : lines)
        {
            JsonObject line = parse(gson, text);
            if (line != null && line.has("timeMs"))
            {
                read(day, line);
            }
        }
        if (day.open != null)
        {
            day.open.ongoing = true;
        }
        return day;
    }

    /** One snapshot or toggle line into the day; anything else, or a malformed line, is skipped. */
    private static void read(Day day, JsonObject line)
    {
        long time;
        int world;
        try
        {
            time = line.get("timeMs").getAsLong();
            world = line.has("world") && !line.get("world").isJsonNull() ? line.get("world").getAsInt() : 0;
        }
        catch (IllegalStateException | ClassCastException | NumberFormatException e)
        {
            return;
        }
        String type = string(line, "type");
        boolean counted = "snapshot".equals(type) ? readSnapshot(day, line, time, world)
            : "toggle".equals(type) && readToggle(day, line, time);
        if (!counted)
        {
            return;
        }
        String rsn = string(line, "rsn");
        if (rsn != null && !rsn.isEmpty())
        {
            day.players.add(rsn);
        }
        if (world > 0)
        {
            day.worlds.add(world);
        }
        day.first = day.first < 0 ? time : Math.min(day.first, time);
        day.last = Math.max(day.last, time);
    }

    /** An enter snapshot opens a visit and a leave closes it; false for any other event. */
    private static boolean readSnapshot(Day day, JsonObject line, long time, int world)
    {
        String event = string(line, "event");
        List<String> on = new ArrayList<>();
        day.sideloaded |= enabled(line.get("plugins"), on);
        if (PluginAudit.ENTER.equals(event))
        {
            day.open = new Visit();
            day.open.startMs = time;
            day.open.world = world;
            day.open.on = on;
            day.visits.add(day.open);
            return true;
        }
        if (!PluginAudit.LEAVE.equals(event))
        {
            return false;
        }
        if (day.open == null)
        {
            // Entered before today's file began: all we have is the leave.
            day.open = new Visit();
            day.open.world = world;
            day.open.on = on;
            day.visits.add(day.open);
        }
        day.open.endMs = time;
        day.open = null;
        return true;
    }

    /** A toggle joins the open visit, or the outside count; false when it is malformed. */
    private static boolean readToggle(Day day, JsonObject line, long time)
    {
        String name = string(line, "name");
        if (name == null || !line.has("enabled"))
        {
            return false;
        }
        boolean enabled;
        try
        {
            enabled = line.get("enabled").getAsBoolean();
        }
        catch (IllegalStateException | ClassCastException e)
        {
            return false;
        }
        if (day.open == null)
        {
            day.outside++;
        }
        else
        {
            day.open.toggles.add(new Toggle(time, name, enabled));
        }
        return true;
    }

    /** Who, which worlds, the time range and the visit count, and the sideloaded marker's key. */
    private static String header(Day day, ZoneId zone)
    {
        StringBuilder head = new StringBuilder();
        head.append("RFL plugin history - ")
            .append(day.players.isEmpty() ? "unknown player" : String.join(" / ", day.players));
        if (!day.worlds.isEmpty())
        {
            List<String> worlds = new ArrayList<>();
            for (int world : day.worlds)
            {
                worlds.add("W" + world);
            }
            head.append(" - ").append(String.join(", ", worlds));
        }
        int visits = day.visits.size();
        head.append('\n').append(DATE.format(at(day.first, zone))).append(' ').append(time(day.first, zone))
            .append(" to ").append(time(day.last, zone)).append(" - ").append(visits)
            .append(visits == 1 ? " house visit" : " house visits").append('\n');
        if (day.sideloaded)
        {
            head.append("* sideloaded plugin\n");
        }
        return head.toString();
    }

    /** One block per visit, newest first; each compared with the older visit after it. */
    private static List<String> blocks(List<Visit> visits, ZoneId zone)
    {
        List<String> blocks = new ArrayList<>();
        for (int i = visits.size() - 1; i >= 0; i--)
        {
            blocks.add(block(visits.get(i), i > 0 ? visits.get(i - 1) : null, zone));
        }
        return blocks;
    }

    /**
     * The code-fenced text: the header and as many blocks, newest first, as fit within
     * {@code limit} (always the newest), then a "… N more visits" line and the tail.
     */
    private static Result fit(String header, List<String> blocks, String tail, int limit, int visits)
    {
        StringBuilder body = new StringBuilder(header);
        int shown = 0;
        for (String block : blocks)
        {
            int left = blocks.size() - shown - 1;
            String more = left > 0 ? moreLine(left) : "";
            // Two fences and their newlines, the text so far, a newline, this block, then what follows it.
            int length = FENCE.length() * 2 + 2 + body.length() + 1 + block.length() + more.length() + tail.length();
            if (shown > 0 && length > limit)
            {
                break;
            }
            body.append('\n').append(block);
            shown++;
        }
        if (shown < blocks.size())
        {
            body.append(moreLine(blocks.size() - shown));
        }
        body.append(tail);
        String text = FENCE + "\n" + body.toString().replaceAll("\n+$", "") + "\n" + FENCE;
        return new Result(text, visits, shown);
    }

    /** "… 4 more visits" (older, left out to fit). */
    private static String moreLine(int count)
    {
        return "\n… " + count + (count == 1 ? " more visit" : " more visits") + " (older, not shown)\n";
    }

    /** One visit: its range, its plugin list (or "same as the visit below"), then its toggles. */
    private static String block(Visit visit, Visit older, ZoneId zone)
    {
        StringBuilder text = new StringBuilder("Visit ");
        text.append(visit.startMs < 0 ? "?" : time(visit.startMs, zone)).append(" to ")
            .append(visit.ongoing ? "now" : visit.endMs < 0 ? "?" : time(visit.endMs, zone));
        if (visit.world > 0)
        {
            text.append(" (W").append(visit.world).append(')');
        }
        text.append('\n');
        if (older != null && older.on.equals(visit.on))
        {
            text.append("On: same as the visit below\n");
        }
        else
        {
            text.append("On (").append(visit.on.size()).append("): ")
                .append(visit.on.isEmpty() ? "none" : String.join(", ", visit.on)).append('\n');
        }
        for (Toggle toggle : visit.toggles)
        {
            text.append("  ").append(toggle.enabled ? '+' : '-').append(toggle.name).append(' ').append(time(toggle.timeMs, zone))
                .append('\n');
        }
        return text.toString();
    }

    /**
     * Fills {@code out} with the names of the plugins on, sorted ignoring case, sideloaded ones
     * marked "*". Returns whether any of them is sideloaded.
     */
    private static boolean enabled(JsonElement plugins, List<String> out)
    {
        boolean sideloaded = false;
        if (plugins == null || !plugins.isJsonArray())
        {
            return false;
        }
        JsonArray list = plugins.getAsJsonArray();
        for (JsonElement element : list)
        {
            if (!element.isJsonObject())
            {
                continue;
            }
            JsonObject plugin = element.getAsJsonObject();
            String name = string(plugin, "name");
            boolean on;
            try
            {
                on = plugin.has("enabled") && plugin.get("enabled").getAsBoolean();
            }
            catch (IllegalStateException | ClassCastException ex)
            {
                continue;
            }
            if (name == null || !on)
            {
                continue;
            }
            boolean side = PluginEntry.SIDELOADED.equals(string(plugin, "source"));
            sideloaded |= side;
            out.add(side ? name + "*" : name);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return sideloaded;
    }

    /** "Copied 3 visits (1,240 chars)", "Copied 3 of 7 visits (1,980 chars)". */
    public static String copiedMessage(Result result)
    {
        String chars = String.format(Locale.US, "%,d chars", result.getText().length());
        if (result.getVisits() == 0)
        {
            return "Copied: no house visits today (" + chars + ")";
        }
        String visits = result.getShown() == result.getVisits()
            ? result.getShown() + (result.getShown() == 1 ? " visit" : " visits")
            : result.getShown() + " of " + result.getVisits() + " visits";
        return "Copied " + visits + " (" + chars + ")";
    }

    private static JsonObject parse(Gson gson, String line)
    {
        if (line == null || line.trim().isEmpty())
        {
            return null;
        }
        try
        {
            JsonElement element = gson.fromJson(line, JsonElement.class);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        }
        catch (JsonParseException | IllegalStateException e)
        {
            return null;
        }
    }

    private static String string(JsonObject object, String key)
    {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive())
        {
            return null;
        }
        return value.getAsString();
    }

    private static ZonedDateTime at(long epochMs, ZoneId zone)
    {
        return Instant.ofEpochMilli(epochMs).atZone(zone);
    }

    private static String time(long epochMs, ZoneId zone)
    {
        return TIME.format(at(epochMs, zone));
    }
}
