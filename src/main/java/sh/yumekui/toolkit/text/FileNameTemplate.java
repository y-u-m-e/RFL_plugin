package sh.yumekui.toolkit.text;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * File names from a user's template: {@code {token}}s replaced from a map, then made safe as a file
 * name on every system (no path separators or characters Windows forbids, no control characters,
 * no reserved device name, trimmed, length-capped). Also numbers a taken name ({@code a.ext} to
 * {@code a-2.ext}). No IO.
 */
public final class FileNameTemplate
{
    /** Characters Windows forbids in a file name, plus both path separators. */
    private static final String FORBIDDEN = "<>:\"/\\|?*";
    /** The first character that is not a control character, and DEL, the one control character above it. */
    private static final char FIRST_PRINTABLE = 0x20;
    private static final char DELETE = 0x7f;
    /** Names Windows reserves for devices, whatever the extension. */
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private static final char REPLACEMENT = '_';

    private FileNameTemplate()
    {
    }

    /**
     * The template with each {@code {token}} replaced by {@code tokens.get(token)}, then made safe
     * ({@link #sanitize}). Null when the template is null or blank, uses a token not in the map, has
     * a stray brace, or nothing safe is left.
     */
    public static String expand(String template, Map<String, String> tokens, int maxLength)
    {
        if (template == null || template.trim().isEmpty())
        {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (at < template.length())
        {
            char next = template.charAt(at);
            if (next == '}')
            {
                return null;
            }
            if (next != '{')
            {
                out.append(next);
                at++;
                continue;
            }
            int close = template.indexOf('}', at);
            if (close < 0)
            {
                return null;
            }
            String value = tokens.get(template.substring(at + 1, close));
            if (value == null)
            {
                return null;
            }
            out.append(value);
            at = close + 1;
        }
        return sanitize(out.toString(), maxLength);
    }

    /**
     * A name safe on Windows, macOS and Linux: forbidden and control characters become {@code _},
     * surrounding spaces and dots go, it is cut to {@code maxLength}, and a reserved device name
     * ({@code CON}, {@code NUL}, {@code COM1}, ...) gets a leading {@code _}. Null when nothing is left.
     */
    public static String sanitize(String name, int maxLength)
    {
        if (name == null)
        {
            return null;
        }
        StringBuilder safeChars = new StringBuilder(name.length());
        for (int at = 0; at < name.length(); at++)
        {
            char next = name.charAt(at);
            boolean unsafe = next < FIRST_PRINTABLE || next == DELETE || FORBIDDEN.indexOf(next) >= 0;
            safeChars.append(unsafe ? REPLACEMENT : next);
        }
        String safe = trim(safeChars.toString());
        if (safe.length() > maxLength)
        {
            safe = trim(safe.substring(0, maxLength));
        }
        if (safe.isEmpty())
        {
            return null;
        }
        String stem = safe.contains(".") ? safe.substring(0, safe.indexOf('.')) : safe;
        if (RESERVED.contains(stem.trim().toUpperCase(Locale.ROOT)))
        {
            safe = REPLACEMENT + safe;
        }
        return safe;
    }

    /** Strips spaces and dots from both ends: Windows drops trailing ones, and a leading dot hides a file. */
    private static String trim(String text)
    {
        int start = 0;
        int end = text.length();
        while (start < end && trimmable(text.charAt(start)))
        {
            start++;
        }
        while (end > start && trimmable(text.charAt(end - 1)))
        {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean trimmable(char character)
    {
        return Character.isWhitespace(character) || character == '.';
    }

    /**
     * {@code a<suffix>} to {@code a-2<suffix>} for {@code n} 2; a name without the suffix gets
     * {@code -n} at the end.
     */
    public static String numbered(String fileName, int number, String suffix)
    {
        if (fileName.endsWith(suffix))
        {
            return fileName.substring(0, fileName.length() - suffix.length()) + "-" + number + suffix;
        }
        return fileName + "-" + number;
    }
}
