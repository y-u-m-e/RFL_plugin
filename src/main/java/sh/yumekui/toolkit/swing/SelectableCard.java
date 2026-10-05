package sh.yumekui.toolkit.swing;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.border.Border;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * A card showing a count that is also a switch: click it (or focus it and press Space or Enter) to pick what
 * it counts. Selected: tinted with its accent and outlined. Hover: lighter. Every state has the
 * same border widths, so selecting moves nothing.
 *
 * <p>Swing EDT only.
 */
public final class SelectableCard extends JPanel
{
    /** The big number's point size on a full card. */
    private static final float NUMBER_SIZE = 32f;
    /** The top accent bar, and the outline on the other sides when selected. */
    private static final int TOP_BAR = 3;
    private static final int OUTLINE = 1;
    /** Padding inside the border: top, sides, bottom. */
    private static final int PAD_TOP = 4;
    private static final int PAD_SIDE = 6;
    private static final int PAD_BOTTOM = 6;
    /** How far the background leans towards the accent: the unselected top bar, selected, selected and hovered. */
    private static final float BAR_TINT = 0.45f;
    private static final float SELECTED_TINT = 0.2f;
    private static final float SELECTED_HOVER_TINT = 0.28f;
    private static final String PICK = "pick";

    private final Color accent;
    private final JLabel number = new JLabel("0");
    private final JLabel caption;
    private boolean selected;
    private boolean hover;

    /**
     * @param compact one line, caption left and a small summary right, for a full-width card
     * @param onPick runs on the EDT when the card is clicked or activated from the keyboard
     */
    public SelectableCard(String text, Color accent, boolean compact, Runnable onPick)
    {
        super(new BorderLayout());
        this.accent = accent;
        number.setFont(compact ? FontManager.getRunescapeBoldFont()
            : FontManager.getRunescapeBoldFont().deriveFont(NUMBER_SIZE));
        number.setForeground(accent);
        caption = new JLabel(text);
        caption.setFont(FontManager.getRunescapeSmallFont());
        if (compact)
        {
            add(caption, BorderLayout.WEST);
            add(number, BorderLayout.EAST);
        }
        else
        {
            add(number, BorderLayout.CENTER);
            add(caption, BorderLayout.SOUTH);
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("Show " + text.toLowerCase() + " below");
        setFocusable(true);
        addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent event)
            {
                onPick.run();
            }

            @Override
            public void mouseEntered(MouseEvent event)
            {
                hover = true;
                restyle();
            }

            @Override
            public void mouseExited(MouseEvent event)
            {
                hover = false;
                restyle();
            }
        });
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), PICK);
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), PICK);
        getActionMap().put(PICK, new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent event)
            {
                onPick.run();
            }
        });
        restyle();
    }

    public void setSelected(boolean selected)
    {
        this.selected = selected;
        restyle();
    }

    public void setCount(int count)
    {
        number.setText(String.valueOf(count));
    }

    /** Replaces the number with a short HTML summary, for a compact card ("A 3  B 2"). */
    public void setSummary(String html)
    {
        number.setText(html);
    }

    private void restyle()
    {
        Border edge = selected
            ? BorderFactory.createMatteBorder(TOP_BAR, OUTLINE, OUTLINE, OUTLINE, accent)
            : BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(TOP_BAR, 0, 0, 0,
                    SidebarWidgets.blend(ColorScheme.DARKER_GRAY_COLOR, accent, BAR_TINT)),
                BorderFactory.createEmptyBorder(0, OUTLINE, OUTLINE, OUTLINE));
        setBorder(BorderFactory.createCompoundBorder(edge,
            BorderFactory.createEmptyBorder(PAD_TOP, PAD_SIDE, PAD_BOTTOM, PAD_SIDE)));
        Color base = hover && !selected ? ColorScheme.DARK_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR;
        setBackground(selected
            ? SidebarWidgets.blend(ColorScheme.DARKER_GRAY_COLOR, accent, hover ? SELECTED_HOVER_TINT : SELECTED_TINT)
            : base);
        caption.setForeground(selected ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
        repaint();
    }
}
