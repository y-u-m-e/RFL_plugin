package com.rfl.replay;

import sh.yumekui.toolkit.text.FileNameTemplate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * The Replay file name setting: a template with {@code {date}} (yyyy-MM-dd), {@code {time}}
 * (HHmmss), {@code {world}} and {@code {player}}, expanded and made safe as a file name on every
 * system by {@link FileNameTemplate}, at most {@link #MAX_LENGTH} characters. The plugin always adds
 * {@link #SUFFIX}. An empty template, an unknown token, a stray brace, or a result that is empty once
 * made safe falls back to {@link #DEFAULT_TEMPLATE}, which is the name replays always had
 * ({@code 2026-10-03_114112_w354}). A name already on disk gets {@code -2}, {@code -3}, ..., chosen
 * where the file is created.
 *
 * <p>No IO; unit-tested on its own.
 */
public final class ReplayFileName
{
    public static final String DEFAULT_TEMPLATE = "{date}_{time}_w{world}";
    static final String SUFFIX = ".rflr.gz";
    /** Longest name before {@link #SUFFIX}; well inside every file system's limit. */
    static final int MAX_LENGTH = 100;
    /** The {@code {player}} value when the local player's name isn't known yet. */
    private static final String UNKNOWN_PLAYER = "unknown";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    private ReplayFileName()
    {
    }

    /**
     * The file name (with {@link #SUFFIX}) for a template, falling back to the default template
     * when it is empty or invalid.
     *
     * @param player the local player's name, or null when unknown
     */
    static String fileName(String template, long epochMs, ZoneId zone, int world, String player)
    {
        ZonedDateTime at = Instant.ofEpochMilli(epochMs).atZone(zone);
        Map<String, String> tokens = Map.of(
            "date", DATE.format(at),
            "time", TIME.format(at),
            "world", String.valueOf(world),
            "player", player == null || player.trim().isEmpty() ? UNKNOWN_PLAYER : player);
        String base = FileNameTemplate.expand(template, tokens, MAX_LENGTH);
        if (base == null)
        {
            base = FileNameTemplate.expand(DEFAULT_TEMPLATE, tokens, MAX_LENGTH);
        }
        return base + SUFFIX;
    }
}
