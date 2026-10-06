package com.rfl.replay;

import com.rfl.contact.Collision;
import sh.yumekui.toolkit.text.FileNameTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Clip file name setting: a template expanded by the same engine as the replay file name
 * ({@link FileNameTemplate}), with every replay token plus clip ones:
 * <ul>
 * <li>{@code {date}} (yyyy-MM-dd) and {@code {time}} (HHmmss): when the clip was saved;</li>
 * <li>{@code {world}} and {@code {player}}: the source recording's world and recorder;</li>
 * <li>{@code {source}}: the source recording's file name without {@link ReplayFileName#SUFFIX};</li>
 * <li>{@code {length}}: the clip's length as {@code m-ss} ({@code 3-00});</li>
 * <li>{@code {collision}}: the latest collision in the clip as {@code <a>-vs-<b>}, the ball carrier
 * first when exactly one of them held it, names with spaces as {@code _}; {@code no-collision}
 * when the clip has none;</li>
 * <li>{@code {collisionTime}}: that collision's start time (HHmmss), or nothing when there is
 * none, in which case one separator next to it goes too, so no double separator is left.</li>
 * </ul>
 * An empty template, an unknown token, a stray brace or a result with nothing safe left falls back
 * to {@link #DEFAULT_TEMPLATE}. {@link ReplayFileName#SUFFIX} is always added, and a taken name gets
 * {@code -2}, {@code -3}, ... where the file is created.
 *
 * <p>No IO; unit-tested on its own.
 */
public final class ClipFileName
{
    public static final String DEFAULT_TEMPLATE = "{date}_{time}_w{world}_{collision}";
    /** {@code {collision}} when the clip has no collision. */
    static final String NO_COLLISION = "no-collision";
    /** Between the two names of {@code {collision}}. */
    static final String VERSUS = "-vs-";
    /** The {@code {player}} value when the recorder's name isn't known. */
    private static final String UNKNOWN = "unknown";
    /**
     * Stands in for an empty {@code {collisionTime}} until a separator beside it is removed: a
     * private-use character, which {@link FileNameTemplate#sanitize} keeps and no name contains.
     */
    private static final char EMPTY_MARK = '';
    /** Characters treated as separators around an empty token. */
    private static final String SEPARATORS = "_- .";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final int SECONDS_PER_MINUTE = 60;

    private ClipFileName()
    {
    }

    /** What the clip tokens are made from. */
    static final class Facts
    {
        final long savedAtMs;
        final int world;
        final String player;
        /** The source recording's file name, with or without the suffix; may be null. */
        final String source;
        final int seconds;
        /** The latest collision in the clip, or null. */
        final Collision collision;

        Facts(long savedAtMs, int world, String player, String source, int seconds, Collision collision)
        {
            this.savedAtMs = savedAtMs;
            this.world = world;
            this.player = player;
            this.source = source;
            this.seconds = seconds;
            this.collision = collision;
        }
    }

    /** The file name (with {@link ReplayFileName#SUFFIX}), falling back to the default template. */
    static String fileName(String template, Facts facts, ZoneId zone)
    {
        Map<String, String> tokens = tokens(facts, zone);
        String base = expand(template, tokens);
        if (base == null)
        {
            base = expand(DEFAULT_TEMPLATE, tokens);
        }
        if (base == null)
        {
            base = UNKNOWN;
        }
        return base + ReplayFileName.SUFFIX;
    }

    static Map<String, String> tokens(Facts facts, ZoneId zone)
    {
        ZonedDateTime at = Instant.ofEpochMilli(facts.savedAtMs).atZone(zone);
        Map<String, String> tokens = new HashMap<>();
        tokens.put("date", DATE.format(at));
        tokens.put("time", TIME.format(at));
        tokens.put("world", String.valueOf(facts.world));
        tokens.put("player", facts.player == null || facts.player.trim().isEmpty() ? UNKNOWN : facts.player);
        tokens.put("source", source(facts.source));
        tokens.put("length", length(facts.seconds));
        tokens.put("collision", collision(facts.collision));
        tokens.put("collisionTime", facts.collision == null ? String.valueOf(EMPTY_MARK)
            : TIME.format(Instant.ofEpochMilli(facts.collision.startMs).atZone(zone)));
        return tokens;
    }

    /** The template expanded and made safe, an empty token's mark removed with one separator; null when invalid. */
    private static String expand(String template, Map<String, String> tokens)
    {
        String expanded = FileNameTemplate.expand(template, tokens, ReplayFileName.MAX_LENGTH);
        if (expanded == null)
        {
            return null;
        }
        return FileNameTemplate.sanitize(dropEmptyMarks(expanded), ReplayFileName.MAX_LENGTH);
    }

    /**
     * Removes each {@link #EMPTY_MARK} together with one separator next to it (the one before it,
     * else the one after), so {@code a_{collisionTime}_b} becomes {@code a_b}, not {@code a__b}.
     */
    static String dropEmptyMarks(String name)
    {
        StringBuilder out = new StringBuilder(name);
        int at;
        while ((at = out.indexOf(String.valueOf(EMPTY_MARK))) >= 0)
        {
            if (at > 0 && SEPARATORS.indexOf(out.charAt(at - 1)) >= 0)
            {
                out.delete(at - 1, at + 1);
            }
            else if (at + 1 < out.length() && SEPARATORS.indexOf(out.charAt(at + 1)) >= 0)
            {
                out.delete(at, at + 2);
            }
            else
            {
                out.deleteCharAt(at);
            }
        }
        return out.toString();
    }

    /** The source file name without the suffix; {@code unknown} when there is none. */
    static String source(String fileName)
    {
        if (fileName == null || fileName.trim().isEmpty())
        {
            return UNKNOWN;
        }
        return fileName.endsWith(ReplayFileName.SUFFIX)
            ? fileName.substring(0, fileName.length() - ReplayFileName.SUFFIX.length()) : fileName;
    }

    /** {@code m-ss}: 180 is {@code 3-00}. */
    static String length(int seconds)
    {
        int total = Math.max(0, seconds);
        return String.format(Locale.ROOT, "%d-%02d", total / SECONDS_PER_MINUTE, total % SECONDS_PER_MINUTE);
    }

    /**
     * {@code <a>-vs-<b>}, the ball carrier first when exactly one of the pair held the ball, else
     * in the collision's own order; {@link #NO_COLLISION} for none.
     */
    static String collision(Collision collision)
    {
        if (collision == null)
        {
            return NO_COLLISION;
        }
        String first = collision.a;
        String second = collision.b;
        List<String> ball = collision.ball;
        if (ball != null && ball.size() == 1 && ball.get(0) != null && ball.get(0).equals(second))
        {
            first = collision.b;
            second = collision.a;
        }
        return name(first) + VERSUS + name(second);
    }

    /** A name as one file-name word: whitespace runs become {@code _}; blank is {@code unknown}. */
    static String name(String name)
    {
        if (name == null || name.trim().isEmpty())
        {
            return UNKNOWN;
        }
        // RSNs hold ordinary and non-breaking spaces; both become one underscore.
        return name.trim().replaceAll("[\\s\\u00A0]+", "_");
    }
}
