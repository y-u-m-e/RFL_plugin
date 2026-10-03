package com.rfl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * The local user's own plugin list, saved on this computer for league refs to collect later: a
 * {@code "type":"snapshot"} line (every installed plugin) on entering a player-owned house, and a
 * {@code "type":"toggle"} line when a plugin is turned on or off while logged in, appended to
 * {@code RUNELITE_DIR/rfl/plugins/YYYY-MM-DD.jsonl} while Log plugin stats is on. Nothing here is
 * ever sent anywhere, and no other player's plugins are known or logged.
 *
 * <p>The latest list is kept in memory for the RFL panel either way ({@link #latest}).
 *
 * <p>Threads: every method is called on the client thread; the encoding and the file write run on
 * the injected executor.
 */
@Slf4j
@Singleton
final class PluginLog
{
    /** One snapshot line. Field names are the JSON keys. */
    static final class Snapshot
    {
        final String type = "snapshot";
        final long timeMs;
        final String rsn;
        final int world;
        final List<PluginEntry> plugins;

        Snapshot(long timeMs, String rsn, int world, List<PluginEntry> plugins)
        {
            this.timeMs = timeMs;
            this.rsn = rsn;
            this.world = world;
            this.plugins = plugins;
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
    private final Path dir;
    private final BooleanSupplier save;
    /** Client thread only. */
    private List<PluginEntry> latest = Collections.emptyList();

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

    /** Remembers a list for the panel without writing it. */
    void remember(List<PluginEntry> plugins)
    {
        latest = Collections.unmodifiableList(new ArrayList<>(plugins));
    }

    /** Remembers the list and, with Log plugin stats on, appends a snapshot line. */
    void snapshot(long timeMs, String rsn, int world, List<PluginEntry> plugins)
    {
        remember(plugins);
        write(new Snapshot(timeMs, rsn, world, latest), timeMs);
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
    }

    /** Applies the toggle to the remembered list and, with Log plugin stats on, appends a toggle line. */
    void toggle(long timeMs, String rsn, int world, String name, boolean enabled)
    {
        applyToggle(name, enabled);
        write(new Toggle(timeMs, rsn, world, name, enabled), timeMs);
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
        final Path file = dir.resolve(CollisionLog.fileName(epochMs));
        try
        {
            Files.createDirectories(dir);
            Files.write(file, (json + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e)
        {
            log.warn("RFL plugins: can't write {}", file, e);
        }
    }
}
