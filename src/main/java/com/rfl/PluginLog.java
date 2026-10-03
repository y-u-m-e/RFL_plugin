package com.rfl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * The local user's own plugin list, saved on this computer for league refs to collect later: a
 * {@code "type":"snapshot"} line (every installed plugin, tagged {@code "event":"enter"} or
 * {@code "event":"leave"}) on entering and leaving a player-owned house, and a
 * {@code "type":"toggle"} line when a plugin is turned on or off while logged in, appended to
 * {@code RUNELITE_DIR/rfl/plugins/YYYY-MM-DD.jsonl} while Log plugin stats is on. Nothing here is
 * ever sent anywhere, and no other player's plugins are known or logged.
 *
 * <p>The latest list and the toggle history are kept in memory for the RFL panel either way
 * ({@link #latest}, {@link #toggles}); on start-up the history is seeded from today's file
 * ({@link #loadToday}).
 *
 * <p>Threads: every method except {@link #toggles}, {@link #loadToday} and {@link #readToday} is
 * called on the client thread; the encoding and the file write run on the injected executor. The
 * toggle history is synchronized.
 */
@Slf4j
@Singleton
final class PluginLog
{
    /** One snapshot line. Field names are the JSON keys. {@code event} is "enter" or "leave". */
    static final class Snapshot
    {
        final String type = "snapshot";
        final long timeMs;
        final String rsn;
        final int world;
        final List<PluginEntry> plugins;
        final String event;

        Snapshot(long timeMs, String rsn, int world, List<PluginEntry> plugins, String event)
        {
            this.timeMs = timeMs;
            this.rsn = rsn;
            this.world = world;
            this.plugins = plugins;
            this.event = event;
        }
    }

    /** One toggle line. Field names are the JSON keys. */
    static final class Toggle
    {
        final String type = "toggle";
        final long timeMs;
        final String rsn;
        final int world;
        final String name;
        final boolean enabled;

        Toggle(long timeMs, String rsn, int world, String name, boolean enabled)
        {
            this.timeMs = timeMs;
            this.rsn = rsn;
            this.world = world;
            this.name = name;
            this.enabled = enabled;
        }
    }

    private final Gson gson;
    private final ScheduledExecutorService executor;
    /** Written on the executor after each successful append, read by the panel on the client thread. */
    private volatile long lastSavedAtMs;
    private final Path dir;
    private final BooleanSupplier save;
    /** Panel history cap. */
    static final int MAX_TOGGLES = 200;

    /** Client thread only. */
    private List<PluginEntry> latest = Collections.emptyList();
    /** Toggles for the panel, oldest first; guarded by itself. */
    private final List<Toggle> toggles = new ArrayList<>();
    /** Bumped whenever the remembered list or the toggle history changes. */
    private final AtomicLong version = new AtomicLong();

    @Inject
    PluginLog(Gson gson, ScheduledExecutorService executor, RflConfig config)
    {
        this(gson, executor, RuneLite.RUNELITE_DIR.toPath().resolve("rfl").resolve("plugins"),
            config::logPluginStats);
    }

    /** @param save whether lines are written to disk right now (the Log plugin stats setting) */
    PluginLog(Gson gson, ScheduledExecutorService executor, Path dir, BooleanSupplier save)
    {
        this.gson = gson;
        this.executor = executor;
        this.dir = dir;
        this.save = save;
    }

    /** Folder the day files are written to. */
    Path dir()
    {
        return dir;
    }

    /** Whether lines are written right now (Log plugin stats). */
    boolean saving()
    {
        return save.getAsBoolean();
    }

    /** The latest known plugin list (last snapshot plus later toggles), for the panel. */
    List<PluginEntry> latest()
    {
        return latest;
    }

    /** Any thread: changes whenever {@link #latest} or the toggle history changes. */
    long version()
    {
        return version.get();
    }

    /** Remembers a list for the panel without writing it. */
    void remember(List<PluginEntry> plugins)
    {
        latest = Collections.unmodifiableList(new ArrayList<>(plugins));
        version.incrementAndGet();
    }

    /**
     * Remembers the list and, with Log plugin stats on, appends a snapshot line tagged
     * {@code event} ("enter" or "leave").
     */
    void snapshot(long timeMs, String rsn, int world, List<PluginEntry> plugins, String event)
    {
        remember(plugins);
        write(new Snapshot(timeMs, rsn, world, latest, event), timeMs);
    }

    /** Updates the remembered list for a toggle; nothing is written. */
    void applyToggle(String name, boolean enabled)
    {
        final List<PluginEntry> next = new ArrayList<>(latest.size());
        for (final PluginEntry e : latest)
        {
            next.add(e.name.equals(name) ? new PluginEntry(e.name, enabled, e.source) : e);
        }
        latest = Collections.unmodifiableList(next);
        version.incrementAndGet();
    }

    /**
     * Applies the toggle to the remembered list, adds it to the panel history and, with Log plugin
     * stats on, appends a toggle line.
     */
    void toggle(long timeMs, String rsn, int world, String name, boolean enabled)
    {
        applyToggle(name, enabled);
        final Toggle toggle = new Toggle(timeMs, rsn, world, name, enabled);
        merge(Collections.singletonList(toggle));
        write(toggle, timeMs);
    }

    /** Today's toggles (local date of {@code nowMs}), newest first. Any thread. */
    List<Toggle> toggles(long nowMs)
    {
        final LocalDate today = localDate(nowMs);
        final List<Toggle> out = new ArrayList<>();
        synchronized (toggles)
        {
            for (int i = toggles.size() - 1; i >= 0; i--)
            {
                final Toggle t = toggles.get(i);
                if (localDate(t.timeMs).equals(today))
                {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** The day file for the local date of an epoch ms, which may not exist. */
    Path dayFile(long nowMs)
    {
        return dir.resolve(CollisionLog.fileName(nowMs));
    }

    /**
     * Executor: today's day file verbatim, or null when it doesn't exist yet.
     *
     * @throws IOException when it exists but can't be read
     */
    String readToday(long nowMs) throws IOException
    {
        final Path file = dayFile(nowMs);
        if (!Files.isRegularFile(file))
        {
            return null;
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** Executor: adds the toggle lines already in today's file to the panel history. */
    void loadToday(long nowMs)
    {
        try
        {
            final Path file = dayFile(nowMs);
            if (Files.isRegularFile(file))
            {
                merge(parseToggles(gson, Files.readAllLines(file, StandardCharsets.UTF_8)));
            }
        }
        catch (IOException e)
        {
            log.warn("RFL plugins: can't read today's plugin file", e);
        }
    }

    /** The toggle lines among day-file lines; anything else, or unparseable, is skipped. */
    static List<Toggle> parseToggles(Gson gson, List<String> lines)
    {
        final List<Toggle> out = new ArrayList<>();
        for (final String line : lines)
        {
            try
            {
                final JsonObject o = gson.fromJson(line, JsonObject.class);
                if (o == null || !"toggle".equals(string(o, "type")) || string(o, "name") == null
                    || !o.has("timeMs") || !o.has("enabled"))
                {
                    continue;
                }
                out.add(new Toggle(o.get("timeMs").getAsLong(), string(o, "rsn"),
                    o.has("world") ? o.get("world").getAsInt() : 0, string(o, "name"),
                    o.get("enabled").getAsBoolean()));
            }
            catch (JsonParseException | IllegalStateException | ClassCastException | NumberFormatException e)
            {
                // A torn or foreign line; skip it.
            }
        }
        return out;
    }

    private static String string(JsonObject o, String key)
    {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    /** Adds toggles not already present (same time, name and state), keeping time order and the cap. */
    private void merge(List<Toggle> add)
    {
        synchronized (toggles)
        {
            for (final Toggle t : add)
            {
                boolean seen = false;
                for (final Toggle have : toggles)
                {
                    if (have.timeMs == t.timeMs && have.enabled == t.enabled && have.name.equals(t.name))
                    {
                        seen = true;
                        break;
                    }
                }
                if (!seen)
                {
                    toggles.add(t);
                }
            }
            toggles.sort((x, y) -> Long.compare(x.timeMs, y.timeMs));
            while (toggles.size() > MAX_TOGGLES)
            {
                toggles.remove(0);
            }
        }
        version.incrementAndGet();
    }

    private static LocalDate localDate(long epochMs)
    {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private void write(Object line, long epochMs)
    {
        if (save.getAsBoolean())
        {
            executor.execute(() -> append(gson.toJson(line), epochMs));
        }
    }

    private void append(String json, long epochMs)
    {
        final Path file = dayFile(epochMs);
        try
        {
            Files.createDirectories(dir);
            Files.write(file, (json + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            lastSavedAtMs = System.currentTimeMillis();
        }
        catch (IOException e)
        {
            log.warn("RFL plugins: can't write {}", file, e);
        }
    }

    /** Any thread: epoch ms the last line reached disk, 0 before any; for the panel's "Saved" tick. */
    long lastSavedAtMs()
    {
        return lastSavedAtMs;
    }
}
