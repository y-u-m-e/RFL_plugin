package sh.yumekui.toolkit.swing;

import javax.swing.JLabel;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * A one-line result under a button ("Copied 3 visits"): shown for a few seconds, then cleared,
 * keeping its height so nothing below moves. Long text truncates with "..." and the full text is
 * the tooltip.
 *
 * <p>Swing EDT only.
 */
public final class CopyFeedbackLabel
{
    /** A space keeps the line's height while it is empty. */
    private static final String BLANK = " ";

    private final JLabel label = SidebarWidgets.truncating();
    private final Timer clearTimer;

    /** @param showMillis how long a message stays up */
    public CopyFeedbackLabel(int showMillis)
    {
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        label.setText(BLANK);
        clearTimer = new Timer(showMillis, e -> setText(BLANK));
        clearTimer.setRepeats(false);
    }

    public JLabel component()
    {
        return label;
    }

    /** Shows {@code message} for the label's time, replacing any message still up. */
    public void show(String message)
    {
        setText(message);
        clearTimer.restart();
    }

    private void setText(String message)
    {
        label.setText(message);
        label.setToolTipText(message == null || message.trim().isEmpty() ? null : message);
    }
}
