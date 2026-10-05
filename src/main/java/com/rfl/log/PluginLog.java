package com.rfl.log;

import sh.yumekui.toolkit.io.DailyJsonlAppender;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;

import net.runelite.client.RuneLite;

/**
 * The local user's own plugin list, saved on this computer for league refs to collect later: a
 * {@code "type":"snapshot"} line (every installed plugin, tagged {@code "event":"enter"} or
 * {@code "event":"leave"}) on entering and leaving a player-owned house, and a
 * {@code "type":"toggle"} line when a plugin is turned on or off while logged in, appended to
 * {@code RUNELITE_DIR/rfl/plugins/YYYY-MM-DD.jsonl}. Always on: this log is the plugin's main
 * feature. Nothing here is ever sent anywhere, and no other player's plugins are known or logged.
 *
 * <p>Threads: {@link #snapshot} and {@link #toggle} are called on the client thread and
 * {@link #readToday} on the executor; the encoding and the file write run on the injected executor.
 */
@Singleton
public final class PluginLog
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
    private final DailyJsonlAppender days;

    @Inject
    PluginLog(Gson gson, ScheduledExecutorService executor)
    {
        this(gson, executor, RuneLite.RUNELITE_DIR.toPath().resolve("rfl").resolve("plugins"));
    }

    PluginLog(Gson gson, ScheduledExecutorService executor, Path dir)
    {
        this.gson = gson;
        this.days = new DailyJsonlAppender(dir, executor);
    }

    /** Appends a snapshot line tagged {@code event} ("enter" or "leave"). */
    void snapshot(long timeMs, String rsn, int world, List<PluginEntry> plugins, String event)
    {
        write(new Snapshot(timeMs, rsn, world, plugins, event), timeMs);
    }

    /** Appends a toggle line. */
    void toggle(long timeMs, String rsn, int world, String name, boolean enabled)
    {
        write(new Toggle(timeMs, rsn, world, name, enabled), timeMs);
    }

    /** The day file for the local date of an epoch ms, which may not exist. */
    Path dayFile(long nowMs)
    {
        return days.dayFile(nowMs);
    }

    /**
     * Executor: today's day file verbatim, or null when it doesn't exist yet.
     *
     * @throws IOException when it exists but can't be read
     */
    public String readToday(long nowMs) throws IOException
    {
        final Path file = dayFile(nowMs);
        if (!Files.isRegularFile(file))
        {
            return null;
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private void write(Object line, long epochMs)
    {
        days.append(() -> gson.toJson(line), epochMs);
    }

    /** Any thread: epoch ms the last line reached disk, 0 before any; for the panel's "Saved" tick. */
    public long lastSavedAtMs()
    {
        return days.lastSavedAtMs();
    }
}
