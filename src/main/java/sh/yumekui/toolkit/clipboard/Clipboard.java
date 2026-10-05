package sh.yumekui.toolkit.clipboard;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import lombok.extern.slf4j.Slf4j;

/** Copies text to the system clipboard, reporting failure instead of throwing. */
@Slf4j
public final class Clipboard
{
    private Clipboard()
    {
    }

    /**
     * Swing EDT: puts {@code text} on the system clipboard.
     *
     * @return false when another program holds the clipboard right now; try again later
     */
    public static boolean copy(String text)
    {
        try
        {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            return true;
        }
        catch (IllegalStateException e)
        {
            log.warn("Clipboard unavailable", e);
            return false;
        }
    }
}
