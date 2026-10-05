package sh.yumekui.toolkit.swing;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * A list header with a destructive action that asks first, inline: normally a hint and the action
 * button; a click swaps in the question with the action and Cancel, and only the second click acts.
 * Nothing pops up, so the panel never loses its place.
 *
 * <p>Swing EDT only.
 */
public final class ConfirmHeader
{
    /** Gaps in pixels: between hint and button, question and buttons, and the two buttons. */
    private static final int HINT_GAP = 4;
    private static final int QUESTION_GAP = 4;
    private static final int BUTTON_GAP = 4;
    /** The question box's left accent bar and padding. */
    private static final int ACCENT_WIDTH = 3;
    private static final int PAD_TOP = 4;
    private static final int PAD_SIDE = 6;
    private static final int PAD_BOTTOM = 6;
    /** Space above and below the header. */
    private static final int HEADER_PAD = 4;

    private final JPanel header = new JPanel(new BorderLayout());
    private final JPanel normal = new JPanel(new BorderLayout(HINT_GAP, 0));
    private final JPanel confirm = new JPanel(new BorderLayout(0, QUESTION_GAP));
    private final JLabel question = new JLabel();
    private final JButton action;
    private final Supplier<String> questionHtml;
    private boolean available = true;

    /**
     * @param hint shown beside the action button normally
     * @param actionText the button's text, on both the first and the confirming click
     * @param actionTip the first button's tooltip
     * @param questionHtml the question, as a Swing HTML string, read each time it is shown
     * @param alert the accent of the question and the confirming button
     * @param onConfirm runs after the second click
     */
    public ConfirmHeader(JComponent hint, String actionText, String actionTip, Supplier<String> questionHtml, Color alert,
        Runnable onConfirm)
    {
        this.questionHtml = questionHtml;
        action = SidebarWidgets.smallButton(actionText);
        action.setToolTipText(actionTip);
        action.addActionListener(e -> showConfirm(true));
        normal.setOpaque(false);
        normal.add(hint, BorderLayout.CENTER);
        normal.add(action, BorderLayout.EAST);

        question.setFont(FontManager.getRunescapeSmallFont());
        question.setForeground(Color.WHITE);
        JButton yes = SidebarWidgets.smallButton(actionText);
        yes.setForeground(alert.brighter());
        yes.addActionListener(e ->
        {
            showConfirm(false);
            onConfirm.run();
        });
        JButton no = SidebarWidgets.smallButton("Cancel");
        no.addActionListener(e -> showConfirm(false));
        JPanel buttons = new JPanel(new GridLayout(1, 2, BUTTON_GAP, 0));
        buttons.setOpaque(false);
        buttons.add(yes);
        buttons.add(no);
        confirm.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        confirm.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, ACCENT_WIDTH, 0, 0, alert),
            BorderFactory.createEmptyBorder(PAD_TOP, PAD_SIDE, PAD_BOTTOM, PAD_SIDE)));
        confirm.add(question, BorderLayout.CENTER);
        confirm.add(buttons, BorderLayout.SOUTH);

        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(HEADER_PAD, 0, HEADER_PAD, 0));
        header.add(normal, BorderLayout.CENTER);
    }

    /** The header, height-capped for a vertical box. */
    public JComponent component()
    {
        return SidebarWidgets.capHeight(header);
    }

    /** Whether there is anything to act on; when not, the button is disabled and any question withdrawn. */
    public void setAvailable(boolean available)
    {
        this.available = available;
        action.setEnabled(available);
        if (!available)
        {
            showConfirm(false);
        }
    }

    private void showConfirm(boolean show)
    {
        header.removeAll();
        if (show && available)
        {
            question.setText(questionHtml.get());
            header.add(confirm, BorderLayout.CENTER);
        }
        else
        {
            header.add(normal, BorderLayout.CENTER);
        }
        header.revalidate();
        header.repaint();
    }
}
