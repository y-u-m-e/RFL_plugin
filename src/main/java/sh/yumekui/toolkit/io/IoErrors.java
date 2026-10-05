package sh.yumekui.toolkit.io;

import java.nio.file.FileSystemException;
import java.util.Locale;

/** Short, user-facing reasons for IO failures, without the file path a message usually is. */
public final class IoErrors
{
    private static final String EXCEPTION = "Exception";

    private IoErrors()
    {
    }

    /**
     * A short reason for a panel or status line: a file-system error's own reason, or its type in
     * words ("access denied") rather than its message, which is often just the path.
     */
    public static String reason(Throwable error)
    {
        if (error instanceof FileSystemException)
        {
            String reason = ((FileSystemException) error).getReason();
            return reason != null && !reason.isEmpty() ? reason : words(error.getClass().getSimpleName());
        }
        String message = error.getMessage();
        return message == null || message.isEmpty() ? words(error.getClass().getSimpleName()) : message;
    }

    /** "AccessDeniedException" to "access denied". */
    static String words(String type)
    {
        String base = type.endsWith(EXCEPTION) ? type.substring(0, type.length() - EXCEPTION.length()) : type;
        return base.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    }
}
