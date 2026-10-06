package com.rfl.replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rfl.contact.Collision;

/**
 * Cuts a replay buffer clip from a {@link ReplayRing.Snapshot}: a self-contained recording of the
 * last {@code clipCycles} that the replay website opens on its own (spec §2.3). In order:
 * <ol>
 * <li>{@code hdr}: the source's, with {@code cyc} set to the clip's first cycle (the website starts
 * its timeline at {@code hdr.cyc}) and a {@code clip} object: {@code {"source":"<source file
 * name>","fromCyc":N,"toCyc":M,"seconds":S}};</li>
 * <li>the {@code plugins} line as it stands at {@code fromCyc}: the source's list with every
 * {@code plugin_toggle} before the window applied, at {@code fromCyc};</li>
 * <li>every {@code model} line the clip refers to ({@code pm} rows, {@code ball.m}, pitch locs),
 * with the delta bases those need, in id order, so each comes before its first use;</li>
 * <li>the pitch active at {@code fromCyc} as one complete pitch: the first pitch with every
 * {@code locs} line merged into its {@code locs} and {@code names}, so the clip has no
 * {@code locs} lines;</li>
 * <li>for each player present at {@code fromCyc}, their state then, all at {@code fromCyc}:
 * {@code spawn}, {@code team}, {@code app}, then one {@code f}, one {@code pm}, any {@code spot},
 * and one {@code tt} line holding everyone's latest row;</li>
 * <li>every buffered line with {@code cyc} in {@code [fromCyc, toCyc]}, in file order (a pitch
 * reload inside the window comes through as its own complete pitch line, as in the source).</li>
 * </ol>
 *
 * <p>No IO and no client: the caller streams the lines to a file ({@link ClipWriter}).
 */
final class ClipBuilder
{
    /** Why a clip came out shorter than asked. */
    enum Shortened
    {
        NONE,
        /** The ring's byte cap dropped the oldest lines (a crowded house). */
        MEMORY_CAP,
        /** Less than the clip length was recorded (or buffered) so far. */
        NOT_ENOUGH
    }

    /** Where the clip's lines go, one JSON text per call, in order. */
    interface Sink
    {
        void line(String json) throws IOException;
    }

    /** What a clip will hold, worked out before anything is written. */
    static final class Plan
    {
        final int fromCyc;
        final int toCyc;
        final int seconds;
        final Shortened shortened;
        final List<ReplayRing.Model> models;
        final ReplayRing.Pitch pitch;
        final List<ReplayRing.PlayerSnap> players;
        /** Each plugin toggled before the window, and its state at {@code fromCyc}. */
        final Map<String, Boolean> toggles;
        final List<ReplayRing.Entry> window;
        /** Lines the clip will have, for progress. */
        final int lines;
        /** The latest collision in the window, or null (for {@code {collision}} in the file name). */
        final Collision collision;

        Plan(int fromCyc, int toCyc, Shortened shortened, List<ReplayRing.Model> models, ReplayRing.Pitch pitch,
            List<ReplayRing.PlayerSnap> players, Map<String, Boolean> toggles, List<ReplayRing.Entry> window,
            int lines, Collision collision)
        {
            this.toggles = toggles;
            this.collision = collision;
            this.fromCyc = fromCyc;
            this.toCyc = toCyc;
            this.seconds = Math.round((toCyc - fromCyc) / (float) ReplayRing.CYCLES_PER_SECOND);
            this.shortened = shortened;
            this.models = models;
            this.pitch = pitch;
            this.players = players;
            this.window = window;
            this.lines = lines;
        }
    }

    private ClipBuilder()
    {
    }

    /** Works out the clip's window, start state and models. */
    static Plan plan(ReplayRing.Snapshot source, int clipCycles)
    {
        int toCyc = source.lastCyc;
        int requested = toCyc - clipCycles;
        int hdrCyc = source.hdr.get("cyc") instanceof Number ? ((Number) source.hdr.get("cyc")).intValue()
            : source.ring[0].cyc;
        // The earliest cycle whose start state is known: after the last line folded into the base.
        int floor = source.evictedThrough == Integer.MIN_VALUE ? hdrCyc : source.evictedThrough + 1;
        int fromCyc = Math.max(requested, Math.min(floor, toCyc));
        Shortened shortened = fromCyc <= requested ? Shortened.NONE
            : source.cappedThrough >= requested ? Shortened.MEMORY_CAP : Shortened.NOT_ENOUGH;

        ReplayRing.State start = source.base.copy();
        List<ReplayRing.Entry> window = new ArrayList<>();
        Collision collision = null;
        int collisionCyc = Integer.MIN_VALUE;
        for (ReplayRing.Entry entry : source.ring)
        {
            if (entry.cyc < fromCyc)
            {
                start.apply(entry);
            }
            else if (entry.cyc <= toCyc)
            {
                window.add(entry);
                if (entry.payload instanceof Collision && entry.cyc >= collisionCyc)
                {
                    collision = (Collision) entry.payload;
                    collisionCyc = entry.cyc;
                }
            }
        }

        ReplayRing.Pitch pitch = startPitch(source.pitches, fromCyc);
        TreeSet<Integer> used = new TreeSet<>();
        for (ReplayRing.PlayerSnap player : start.players.values())
        {
            if (player.model >= 0)
            {
                used.add(player.model);
            }
        }
        if (pitch != null)
        {
            addLocModels(pitch.line.get("locs"), used);
            if (pitch.first)
            {
                for (Map<String, Object> locs : source.locs)
                {
                    addLocModels(locs.get("locs"), used);
                }
            }
        }
        for (ReplayRing.Entry entry : window)
        {
            addModels(entry, used);
        }
        List<ReplayRing.Model> models = closure(source.models, used);

        List<ReplayRing.PlayerSnap> players = new ArrayList<>(new TreeMap<>(start.players).values());
        int lines = 1 + (source.plugins == null ? 0 : 1) + models.size() + (pitch == null ? 0 : 1)
            + startLines(players) + window.size();
        return new Plan(fromCyc, toCyc, shortened, models, pitch, players, start.toggles, window, lines, collision);
    }

    /** The pitch active at {@code cyc}: the last one starting at or before it, else the earliest. */
    static ReplayRing.Pitch startPitch(List<ReplayRing.Pitch> pitches, int cyc)
    {
        ReplayRing.Pitch best = null;
        for (ReplayRing.Pitch pitch : pitches)
        {
            if (pitch.cyc <= cyc)
            {
                best = pitch;
            }
        }
        return best != null || pitches.isEmpty() ? best : pitches.get(0);
    }

    private static void addModels(ReplayRing.Entry entry, TreeSet<Integer> used)
    {
        if ("pm".equals(entry.type))
        {
            for (int[] row : ReplayRing.rows(entry.json))
            {
                if (row.length > 1)
                {
                    used.add(row[1]);
                }
            }
        }
        else if ("ball".equals(entry.type) && entry.payload instanceof Integer)
        {
            used.add((Integer) entry.payload);
        }
        else if ("pitch".equals(entry.type))
        {
            addLocModels(((ReplayRing.Pitch) entry.payload).line.get("locs"), used);
        }
    }

    /** Loc rows are {@code [modelId, x, y, height, locId, kind]}. */
    private static void addLocModels(Object rows, TreeSet<Integer> used)
    {
        if (!(rows instanceof List))
        {
            return;
        }
        for (Object row : (List<?>) rows)
        {
            if (row instanceof Object[] && ((Object[]) row).length > 0 && ((Object[]) row)[0] instanceof Number)
            {
                used.add(((Number) ((Object[]) row)[0]).intValue());
            }
        }
    }

    /** The used models plus every delta base they need, in id order (a base always has the lower id). */
    private static List<ReplayRing.Model> closure(List<ReplayRing.Model> all, TreeSet<Integer> used)
    {
        Map<Integer, ReplayRing.Model> byId = new HashMap<>();
        for (ReplayRing.Model model : all)
        {
            byId.put(model.id, model);
        }
        TreeSet<Integer> needed = new TreeSet<>();
        for (Integer id : used)
        {
            Integer next = id;
            while (next != null && next >= 0 && needed.add(next))
            {
                ReplayRing.Model model = byId.get(next);
                next = model == null ? null : model.base;
            }
        }
        List<ReplayRing.Model> out = new ArrayList<>();
        for (Integer id : needed)
        {
            ReplayRing.Model model = byId.get(id);
            if (model != null)
            {
                out.add(model);
            }
        }
        return out;
    }

    private static int startLines(List<ReplayRing.PlayerSnap> players)
    {
        int lines = 0;
        boolean rows = false;
        boolean models = false;
        boolean tiles = false;
        for (ReplayRing.PlayerSnap player : players)
        {
            lines += 1 + (player.team == null ? 0 : 1) + (player.app == null ? 0 : 1) + (player.spot == null ? 0 : 1);
            rows |= player.row != null;
            models |= player.model >= 0;
            tiles |= player.tile != null;
        }
        return lines + (rows ? 1 : 0) + (models ? 1 : 0) + (tiles ? 1 : 0);
    }

    /** Writes the planned clip's lines to {@code sink}, in order. */
    static void write(Gson gson, ReplayRing.Snapshot source, Plan plan, Sink sink) throws IOException
    {
        int from = plan.fromCyc;
        sink.line(gson.toJson(hdr(source, plan)));
        if (source.plugins != null)
        {
            sink.line(gson.toJson(plugins(gson, source.plugins, plan.toggles, from)));
        }
        Inflater inflater = new Inflater();
        try
        {
            for (ReplayRing.Model model : plan.models)
            {
                sink.line(inflate(inflater, model));
            }
        }
        finally
        {
            inflater.end();
        }
        if (plan.pitch != null)
        {
            sink.line(gson.toJson(startPitchLine(plan.pitch, source.locs, from)));
        }
        writeStart(gson, plan.players, from, sink);
        for (ReplayRing.Entry entry : plan.window)
        {
            if ("pitch".equals(entry.type))
            {
                if (entry.payload != plan.pitch)
                {
                    sink.line(gson.toJson(((ReplayRing.Pitch) entry.payload).line));
                }
                continue;
            }
            sink.line(entry.json);
        }
    }

    /**
     * The source's {@code plugins} line as it stands at {@code fromCyc}: each listed plugin toggled
     * before the window takes its latest state, and the line moves to the clip's start.
     */
    static JsonObject plugins(Gson gson, String json, Map<String, Boolean> toggles, int fromCyc)
    {
        JsonObject line = gson.fromJson(json, JsonObject.class);
        line.addProperty("cyc", fromCyc);
        JsonElement list = line.get("list");
        if (list != null && list.isJsonArray())
        {
            for (JsonElement item : list.getAsJsonArray())
            {
                if (!item.isJsonObject())
                {
                    continue;
                }
                JsonObject plugin = item.getAsJsonObject();
                JsonElement name = plugin.get("name");
                Boolean enabled = name != null && name.isJsonPrimitive() ? toggles.get(name.getAsString()) : null;
                if (enabled != null)
                {
                    plugin.addProperty("enabled", enabled);
                }
            }
        }
        return line;
    }

    /** The source's hdr with {@code cyc} at the clip's start and the {@code clip} object added. */
    static Map<String, Object> hdr(ReplayRing.Snapshot source, Plan plan)
    {
        Map<String, Object> hdr = new LinkedHashMap<>(source.hdr);
        hdr.put("cyc", plan.fromCyc);
        Map<String, Object> clip = new LinkedHashMap<>();
        clip.put("source", source.sourceFile);
        clip.put("fromCyc", plan.fromCyc);
        clip.put("toCyc", plan.toCyc);
        clip.put("seconds", plan.seconds);
        hdr.put("clip", clip);
        return hdr;
    }

    /**
     * The start pitch as one complete line: the first pitch gets every {@code locs} line's rows and
     * names merged in; its {@code cyc} moves up to the clip's start when it began before it.
     */
    static Map<String, Object> startPitchLine(ReplayRing.Pitch pitch, List<Map<String, Object>> locs, int fromCyc)
    {
        Map<String, Object> line = new LinkedHashMap<>(pitch.line);
        if (pitch.cyc <= fromCyc)
        {
            line.put("cyc", fromCyc);
        }
        if (pitch.first && !locs.isEmpty())
        {
            List<Object> rows = new ArrayList<>();
            Map<String, Object> names = new LinkedHashMap<>();
            addAll(rows, names, pitch.line);
            for (Map<String, Object> more : locs)
            {
                addAll(rows, names, more);
            }
            line.put("locs", rows);
            line.put("names", names);
        }
        return line;
    }

    @SuppressWarnings("unchecked")
    private static void addAll(List<Object> rows, Map<String, Object> names, Map<String, Object> line)
    {
        Object lineRows = line.get("locs");
        if (lineRows instanceof List)
        {
            rows.addAll((List<Object>) lineRows);
        }
        Object lineNames = line.get("names");
        if (lineNames instanceof Map)
        {
            names.putAll((Map<String, Object>) lineNames);
        }
    }

    /** Each present player's spawn, team, app, then everyone's f, pm, spot and tt, all at {@code from}. */
    private static void writeStart(Gson gson, List<ReplayRing.PlayerSnap> players, int from, Sink sink)
        throws IOException
    {
        List<int[]> rows = new ArrayList<>();
        List<int[]> models = new ArrayList<>();
        List<int[]> tiles = new ArrayList<>();
        for (ReplayRing.PlayerSnap player : players)
        {
            sink.line(gson.toJson(ReplayLines.spawn(from, player.index, player.name)));
            if (player.team != null)
            {
                sink.line(withCyc(player.team, from));
            }
            if (player.app != null)
            {
                sink.line(withCyc(player.app, from));
            }
            if (player.row != null)
            {
                rows.add(player.row);
            }
            if (player.model >= 0)
            {
                models.add(new int[] { player.index, player.model });
            }
            if (player.tile != null)
            {
                tiles.add(player.tile);
            }
        }
        if (!rows.isEmpty())
        {
            sink.line(gson.toJson(ReplayLines.poses(from, rows)));
        }
        if (!models.isEmpty())
        {
            sink.line(gson.toJson(ReplayLines.playerModels(from, models)));
        }
        for (ReplayRing.PlayerSnap player : players)
        {
            if (player.spot != null)
            {
                sink.line(withCyc(player.spot, from));
            }
        }
        if (!tiles.isEmpty())
        {
            sink.line(gson.toJson(ReplayLines.trueTiles(from, tiles)));
        }
    }

    /**
     * The line with its first {@code "cyc":N} replaced by {@code cyc}. Every timed line has
     * {@code t} then {@code cyc} first ({@link ReplayLines}), so the first match is the line's own.
     */
    static String withCyc(String json, int cyc)
    {
        String key = "\"cyc\":";
        int at = json.indexOf(key);
        if (at < 0)
        {
            return json;
        }
        int start = at + key.length();
        int end = start;
        if (end < json.length() && json.charAt(end) == '-')
        {
            end++;
        }
        while (end < json.length() && Character.isDigit(json.charAt(end)))
        {
            end++;
        }
        return json.substring(0, start) + cyc + json.substring(end);
    }

    private static String inflate(Inflater inflater, ReplayRing.Model model) throws IOException
    {
        inflater.reset();
        inflater.setInput(model.deflated);
        byte[] out = new byte[model.rawLength];
        int at = 0;
        try
        {
            while (at < out.length && !inflater.finished())
            {
                int n = inflater.inflate(out, at, out.length - at);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary()))
                {
                    break;
                }
                at += n;
            }
        }
        catch (DataFormatException e)
        {
            throw new IOException("a buffered model was damaged", e);
        }
        if (at != out.length)
        {
            throw new IOException("a buffered model was damaged");
        }
        return new String(out, StandardCharsets.UTF_8);
    }
}
