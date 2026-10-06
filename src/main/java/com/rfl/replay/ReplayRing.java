package com.rfl.replay;

import com.rfl.contact.Collision;
import sh.yumekui.toolkit.io.GzipNdjsonWriter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;

import lombok.extern.slf4j.Slf4j;

/**
 * The replay buffer behind "Save last 3 min": the open recording's last few minutes, kept in memory
 * as the lines that reached the file, so a clip can be cut from it at any moment ({@link ClipBuilder}).
 *
 * <p>It is the replay writer's {@link GzipNdjsonWriter.LineTap}, so it runs on the writer's thread,
 * sees exactly what the file got, in file order, and costs the client thread nothing. It keeps:
 * <ul>
 * <li>a ring of every line with a {@code cyc}, as its JSON text, for the last {@link #setKeepCycles
 * keep window} and at most {@link #setCapBytes cap} bytes. A line leaving the ring is folded into
 * the <em>base state</em> ({@link State}): each present player's spawn, latest {@code app},
 * {@code team}, {@code f} row, {@code pm} model, {@code tt} tile and {@code spot} set, and each
 * plugin toggle, as of the newest line evicted. Row lines keep only their text: their rows are
 * read back from it when needed ({@link #rows}), so a crowded house costs its JSON and no more;</li>
 * <li>the {@code hdr} line and the {@code plugins} line;</li>
 * <li>every {@code model} line by id, deflated (a later {@code pm} may reuse any earlier id);</li>
 * <li>the {@code pitch} lines that can still be the one active at a clip's start, and the file's
 * {@code locs} lines while its first pitch can.</li>
 * </ul>
 *
 * <p>{@link #snapshot} hands those out as immutable references, without copying a line.
 *
 * <p>Threads: the writer's drain task only (the tap calls, {@link #snapshot}, {@link #clear}); the
 * keep window and cap are volatile and may be set from any thread. Never throws into the writer: a
 * failure is logged once and the buffer stops until the next file.
 */
@Slf4j
final class ReplayRing implements GzipNdjsonWriter.LineTap
{
    /** Client cycles per second: one ClientTick, 20 ms (spec §2.1). */
    static final int CYCLES_PER_SECOND = 50;
    /** Default byte cap of the ring: a crowded house can't take more than this for the window. */
    static final long DEFAULT_CAP_BYTES = 32L * 1024 * 1024;
    /**
     * Rough JVM cost of one ring entry beyond its JSON text (the entry, the String and its byte
     * array headers, the deque slot), so the cap is about real memory rather than characters.
     */
    static final int ENTRY_OVERHEAD = 96;
    /** Where a row line's rows start in its text. */
    private static final String ROWS_KEY = "\"p\":[";
    /** Fields in the widest row ({@code f}). */
    private static final int ROW_FIELDS = 8;

    /** One ring line: its cycle and text, plus what the base state and model closure need of it. */
    static final class Entry
    {
        final int cyc;
        final String type;
        /** The line as written; null for a pitch, whose map is kept instead. */
        final String json;
        /** Player index for spawn, despawn, app, team, spot; -1 otherwise. */
        final int index;
        /**
         * spawn: the name; pitch: the {@link Pitch}; ball: the model id; ev: the {@link Collision},
         * for a collision; plugin_toggle: the plugin's name. ({@code f}, {@code pm} and {@code tt}
         * rows are read back from {@link #json}.)
         */
        final Object payload;
        /** spot: true when the set is empty (all spot anims ended); plugin_toggle: enabled. */
        final boolean flag;
        final long bytes;

        Entry(int cyc, String type, String json, int index, Object payload, boolean flag, long bytes)
        {
            this.cyc = cyc;
            this.type = type;
            this.json = json;
            this.index = index;
            this.payload = payload;
            this.flag = flag;
            this.bytes = bytes;
        }
    }

    /** A pitch line as built (complete, never touched again), and whether it is the file's first. */
    static final class Pitch
    {
        final Map<String, Object> line;
        final int cyc;
        final boolean first;

        Pitch(Map<String, Object> line, int cyc, boolean first)
        {
            this.line = line;
            this.cyc = cyc;
            this.first = first;
        }
    }

    /** A model line, deflated; {@code base} is the delta base's id, or -1. */
    static final class Model
    {
        final int id;
        final int base;
        final byte[] deflated;
        final int rawLength;

        Model(int id, int base, byte[] deflated, int rawLength)
        {
            this.id = id;
            this.base = base;
            this.deflated = deflated;
            this.rawLength = rawLength;
        }
    }

    /** One player's state at a cycle, for the clip's start (spec: who, how they look, what pose). */
    static final class PlayerSnap
    {
        final int index;
        final String name;
        String app;
        String team;
        String spot;
        int[] row;
        int[] tile;
        int model = -1;

        PlayerSnap(int index, String name)
        {
            this.index = index;
            this.name = name;
        }

        PlayerSnap copy()
        {
            PlayerSnap copy = new PlayerSnap(index, name);
            copy.app = app;
            copy.team = team;
            copy.spot = spot;
            copy.row = row;
            copy.tile = tile;
            copy.model = model;
            return copy;
        }
    }

    /**
     * What a clip's start needs to know from before its window: who is present and their latest
     * state, and every plugin toggle. Folded forward one line at a time ({@link #apply}).
     */
    static final class State
    {
        /** Present players by index. */
        final Map<Integer, PlayerSnap> players = new LinkedHashMap<>();
        /** Each toggled plugin's latest state, by name. */
        final Map<String, Boolean> toggles = new LinkedHashMap<>();

        State copy()
        {
            State copy = new State();
            for (Map.Entry<Integer, PlayerSnap> player : players.entrySet())
            {
                copy.players.put(player.getKey(), player.getValue().copy());
            }
            copy.toggles.putAll(toggles);
            return copy;
        }

        /** Folds one line in. */
        void apply(Entry entry)
        {
            switch (entry.type)
            {
                case "spawn":
                    players.put(entry.index, new PlayerSnap(entry.index, (String) entry.payload));
                    break;
                case "despawn":
                    players.remove(entry.index);
                    break;
                case "app":
                case "team":
                case "spot":
                    applyText(entry);
                    break;
                case "f":
                case "pm":
                case "tt":
                    applyRows(entry);
                    break;
                case "plugin_toggle":
                    if (entry.payload instanceof String)
                    {
                        toggles.put((String) entry.payload, entry.flag);
                    }
                    break;
                default:
                    break;
            }
        }

        private void applyText(Entry entry)
        {
            PlayerSnap snap = players.get(entry.index);
            if (snap == null)
            {
                return;
            }
            if ("app".equals(entry.type))
            {
                snap.app = entry.json;
            }
            else if ("team".equals(entry.type))
            {
                snap.team = entry.json;
            }
            else
            {
                snap.spot = entry.flag ? null : entry.json;
            }
        }

        private void applyRows(Entry entry)
        {
            for (int[] row : rows(entry.json))
            {
                PlayerSnap snap = row.length == 0 ? null : players.get(row[0]);
                if (snap == null)
                {
                    continue;
                }
                if ("f".equals(entry.type))
                {
                    snap.row = row;
                }
                else if ("pm".equals(entry.type))
                {
                    snap.model = row.length > 1 ? row[1] : -1;
                }
                else
                {
                    snap.tile = row;
                }
            }
        }
    }

    /** Everything a clip needs, as immutable references taken on the writer's thread. */
    static final class Snapshot
    {
        final String sourceFile;
        final Map<String, Object> hdr;
        final String plugins;
        /** Every model so far, in id order. */
        final List<Model> models;
        final List<Pitch> pitches;
        /** The first pitch's {@code locs} lines (empty once it can no longer start a clip). */
        final List<Map<String, Object>> locs;
        /** The state after the last evicted line; a copy. */
        final State base;
        final Entry[] ring;
        /** Newest cycle evicted from the ring, or {@link Integer#MIN_VALUE} when none. */
        final int evictedThrough;
        /** Newest cycle evicted because of the byte cap (not age), or {@link Integer#MIN_VALUE}. */
        final int cappedThrough;
        /** Newest cycle written. */
        final int lastCyc;

        Snapshot(String sourceFile, Map<String, Object> hdr, String plugins, List<Model> models, List<Pitch> pitches,
            List<Map<String, Object>> locs, State base, Entry[] ring, int evictedThrough,
            int cappedThrough, int lastCyc)
        {
            this.sourceFile = sourceFile;
            this.hdr = hdr;
            this.plugins = plugins;
            this.models = models;
            this.pitches = pitches;
            this.locs = locs;
            this.base = base;
            this.ring = ring;
            this.evictedThrough = evictedThrough;
            this.cappedThrough = cappedThrough;
            this.lastCyc = lastCyc;
        }
    }

    private volatile int keepCycles;
    private volatile long capBytes;

    private final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
    private final byte[] deflateBuffer = new byte[64 * 1024];

    private String sourceFile;
    private Map<String, Object> hdr;
    private String plugins;
    private final TreeMap<Integer, Model> models = new TreeMap<>();
    private final List<Pitch> pitches = new ArrayList<>();
    private final List<Map<String, Object>> locs = new ArrayList<>();
    private final ArrayDeque<Entry> ring = new ArrayDeque<>();
    private State base = new State();
    private long ringBytes;
    private long modelBytes;
    private int evictedThrough = Integer.MIN_VALUE;
    private int cappedThrough = Integer.MIN_VALUE;
    private int lastCyc = Integer.MIN_VALUE;
    private boolean sawFirstPitch;
    /** Set after a failure: nothing more is kept until the next file. */
    private boolean broken;

    /**
     * @param keepSeconds how much the ring keeps (the clip length plus a margin)
     * @param capBytes the most the ring may hold
     */
    ReplayRing(int keepSeconds, long capBytes)
    {
        setKeepSeconds(keepSeconds);
        setCapBytes(capBytes);
    }

    /** Any thread: how many seconds of lines the ring keeps. */
    void setKeepSeconds(int seconds)
    {
        setKeepCycles(Math.max(1, seconds) * CYCLES_PER_SECOND);
    }

    void setKeepCycles(int cycles)
    {
        keepCycles = cycles;
    }

    /** Any thread: the most bytes the ring may hold before its oldest lines go early. */
    void setCapBytes(long bytes)
    {
        capBytes = bytes;
    }

    @Override
    public void opened(Path file)
    {
        clear();
        sourceFile = file == null || file.getFileName() == null ? null : file.getFileName().toString();
    }

    /** Writer's thread: forgets everything (a new file, or the recording closed). */
    void clear()
    {
        sourceFile = null;
        hdr = null;
        plugins = null;
        models.clear();
        pitches.clear();
        locs.clear();
        ring.clear();
        base = new State();
        ringBytes = 0;
        modelBytes = 0;
        evictedThrough = Integer.MIN_VALUE;
        cappedThrough = Integer.MIN_VALUE;
        lastCyc = Integer.MIN_VALUE;
        sawFirstPitch = false;
        broken = false;
    }

    @Override
    public void written(Object source, String json)
    {
        if (broken)
        {
            return;
        }
        try
        {
            take(source, json);
        }
        catch (RuntimeException e)
        {
            broken = true;
            clearKept();
            log.warn("RFL replay buffer: stopped keeping lines until the next file", e);
        }
    }

    /** Drops what is kept after a failure, so a half-built buffer can't make a wrong clip. */
    private void clearKept()
    {
        models.clear();
        pitches.clear();
        locs.clear();
        ring.clear();
        base = new State();
        ringBytes = 0;
        modelBytes = 0;
        hdr = null;
    }

    /** Uncompressed bytes the ring holds (JSON text plus per-entry overhead). */
    long ringBytes()
    {
        return ringBytes;
    }

    /** Deflated bytes of the kept model lines. */
    long modelBytes()
    {
        return modelBytes;
    }

    int ringSize()
    {
        return ring.size();
    }

    int modelCount()
    {
        return models.size();
    }

    @SuppressWarnings("unchecked")
    private void take(Object source, String json)
    {
        if (source instanceof String)
        {
            // Only team lines are written as ready-made JSON.
            takeTeam(json);
            return;
        }
        if (!(source instanceof Map))
        {
            return;
        }
        Map<String, Object> line = (Map<String, Object>) source;
        Object type = line.get("t");
        if (!(type instanceof String))
        {
            return;
        }
        switch ((String) type)
        {
            case "hdr":
                hdr = line;
                return;
            case "plugins":
                plugins = json;
                return;
            case "model":
                takeModel(line, json);
                return;
            case "locs":
                if (pitches.size() > 0 && pitches.get(0).first)
                {
                    locs.add(line);
                }
                return;
            case "pitch":
                takePitch(line, json);
                return;
            default:
                takeTimed((String) type, line, json);
        }
    }

    private void takeModel(Map<String, Object> line, String json)
    {
        int id = ((Number) line.get("id")).intValue();
        Object base = line.get("base");
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        byte[] deflated = deflate(raw);
        Model old = models.put(id, new Model(id, base instanceof Number ? ((Number) base).intValue() : -1, deflated,
            raw.length));
        modelBytes += deflated.length - (old == null ? 0 : old.deflated.length);
    }

    private byte[] deflate(byte[] raw)
    {
        deflater.reset();
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
        while (!deflater.finished())
        {
            int n = deflater.deflate(deflateBuffer);
            out.write(deflateBuffer, 0, n);
        }
        return out.toByteArray();
    }

    private void takePitch(Map<String, Object> line, String json)
    {
        int cyc = cycOf(line);
        Pitch pitch = new Pitch(line, cyc, !sawFirstPitch);
        sawFirstPitch = true;
        pitches.add(pitch);
        // In the ring too, so a pitch written inside a clip's window goes out in its place there.
        add(new Entry(cyc, "pitch", null, -1, pitch, false, ENTRY_OVERHEAD + json.length()));
    }

    private void takeTeam(String json)
    {
        int cyc = intAfter(json, "\"cyc\":");
        int index = intAfter(json, "\"i\":");
        add(new Entry(cyc, "team", json, index, null, false, ENTRY_OVERHEAD + json.length()));
    }

    private void takeTimed(String type, Map<String, Object> line, String json)
    {
        Object cycValue = line.get("cyc");
        if (!(cycValue instanceof Number))
        {
            return;
        }
        int cyc = ((Number) cycValue).intValue();
        long bytes = ENTRY_OVERHEAD + json.length();
        int index = -1;
        Object payload = null;
        boolean flag = false;
        switch (type)
        {
            case "spawn":
                index = intOf(line.get("i"));
                payload = line.get("name");
                break;
            case "despawn":
            case "app":
                index = intOf(line.get("i"));
                break;
            case "spot":
                index = intOf(line.get("i"));
                Object spots = line.get("s");
                flag = spots instanceof List && ((List<?>) spots).isEmpty();
                break;
            case "plugin_toggle":
                payload = line.get("name");
                flag = Boolean.TRUE.equals(line.get("enabled"));
                break;
            case "ball":
                Object model = line.get("m");
                payload = model instanceof Number ? ((Number) model).intValue() : null;
                break;
            case "ev":
                // Collisions reach the replay already team-gated, the same ones the panel lists.
                Object event = line.get("e");
                payload = event instanceof Collision ? event : null;
                break;
            default:
                break;
        }
        add(new Entry(cyc, type, json, index, payload, flag, bytes));
    }

    private void add(Entry entry)
    {
        ring.addLast(entry);
        ringBytes += entry.bytes;
        lastCyc = Math.max(lastCyc, entry.cyc);
        trim();
    }

    /** Evicts from the head while it is older than the keep window or the ring is over its cap. */
    private void trim()
    {
        int keep = keepCycles;
        long cap = capBytes;
        while (!ring.isEmpty())
        {
            Entry head = ring.peekFirst();
            boolean old = lastCyc - head.cyc > keep;
            boolean over = ringBytes > cap && ring.size() > 1;
            if (!old && !over)
            {
                break;
            }
            ring.pollFirst();
            ringBytes -= head.bytes;
            evictedThrough = Math.max(evictedThrough, head.cyc);
            if (!old)
            {
                cappedThrough = Math.max(cappedThrough, head.cyc);
            }
            base.apply(head);
        }
        prunePitches();
    }

    /**
     * Drops pitches that can no longer be the one active at a clip's start: one whose successor
     * starts at or before the oldest cycle a clip can begin at.
     */
    private void prunePitches()
    {
        while (pitches.size() >= 2 && pitches.get(1).cyc <= evictedThrough + 1)
        {
            if (pitches.remove(0).first)
            {
                locs.clear();
            }
        }
    }

    /**
     * The int rows of an {@code f}, {@code pm} or {@code tt} line's {@code "p":[[..],..]}, read from
     * the line's text as written ({@link ReplayLines}); empty when it has none.
     */
    static List<int[]> rows(String json)
    {
        List<int[]> rows = new ArrayList<>();
        int at = json.indexOf(ROWS_KEY);
        if (at < 0)
        {
            return rows;
        }
        at += ROWS_KEY.length();
        int[] values = new int[ROW_FIELDS];
        while (at < json.length() && json.charAt(at) != ']')
        {
            if (json.charAt(at) != '[')
            {
                at++;
                continue;
            }
            at++;
            int count = 0;
            while (at < json.length() && json.charAt(at) != ']')
            {
                char c = json.charAt(at);
                if (c != '-' && !Character.isDigit(c))
                {
                    at++;
                    continue;
                }
                int end = at + 1;
                while (end < json.length() && Character.isDigit(json.charAt(end)))
                {
                    end++;
                }
                if (count == values.length)
                {
                    values = Arrays.copyOf(values, count * 2);
                }
                values[count++] = Integer.parseInt(json.substring(at, end));
                at = end;
            }
            rows.add(Arrays.copyOf(values, count));
            at++;
        }
        return rows;
    }

    /**
     * Writer's thread: the buffer as immutable references, for a clip built elsewhere. Copies
     * references only (the ring's entries, the model list), plus the few players' base state.
     * Null when there is nothing to cut a clip from (no file, or nothing with a cycle yet).
     */
    Snapshot snapshot()
    {
        if (broken || hdr == null || ring.isEmpty())
        {
            return null;
        }
        return new Snapshot(sourceFile, hdr, plugins, Collections.unmodifiableList(new ArrayList<>(models.values())),
            Collections.unmodifiableList(new ArrayList<>(pitches)), Collections.unmodifiableList(new ArrayList<>(locs)),
            base.copy(), ring.toArray(new Entry[0]), evictedThrough, cappedThrough, lastCyc);
    }

    private static int cycOf(Map<String, Object> line)
    {
        Object cyc = line.get("cyc");
        return cyc instanceof Number ? ((Number) cyc).intValue() : Integer.MIN_VALUE;
    }

    private static int intOf(Object value)
    {
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    /** The int right after {@code key} in hand-built JSON ({@link ReplayLines#team}); -1 when absent. */
    static int intAfter(String json, String key)
    {
        int at = json.indexOf(key);
        if (at < 0)
        {
            return -1;
        }
        at += key.length();
        int end = at;
        if (end < json.length() && json.charAt(end) == '-')
        {
            end++;
        }
        while (end < json.length() && Character.isDigit(json.charAt(end)))
        {
            end++;
        }
        return end == at ? -1 : Integer.parseInt(json.substring(at, end));
    }
}
