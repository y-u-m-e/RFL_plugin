package sh.yumekui.toolkit.swing;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseListener;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Small Swing builders for a sidebar panel whose cards never change size: fixed-height rows,
 * labels that truncate with "..." instead of widening the panel, fixed-width labels for changing
 * numbers, height caps for vertical boxes, a status dot and a scrollable list display.
 *
 * <p>Swing EDT only.
 */
public final class SidebarWidgets
{
    /** Gap between a button's text and its edge, in pixels. */
    public static final Insets BUTTON_MARGIN = new Insets(0, 6, 0, 6);
    /** A list row's padding: top, left, bottom, right. */
    private static final int ROW_PAD_Y = 4;
    private static final int ROW_PAD_X = 6;
    /** Space above and below an empty list's message. */
    private static final int EMPTY_PAD = 6;
    /** A fixed-width label's extra pixels, so a sample that fits exactly isn't cut by rounding. */
    private static final int FIT_SLACK = 2;

    private SidebarWidgets()
    {
    }

    /** A vertical list that is as tall as its rows and as wide as its parent. */
    public static JPanel vertical()
    {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        list.setAlignmentX(Component.LEFT_ALIGNMENT);
        return list;
    }

    /** A list row: as wide as its list, only as tall as its content, with a divider underneath. */
    public static JPanel row()
    {
        JPanel row = new JPanel(new BorderLayout(0, 1))
        {
            @Override
            public Dimension getMaximumSize()
            {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
            BorderFactory.createEmptyBorder(ROW_PAD_Y, ROW_PAD_X, ROW_PAD_Y, ROW_PAD_X)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    /** A row of exactly {@code height} pixels, as wide as its parent gives it. */
    public static JPanel fixedRow(LayoutManager layout, int height)
    {
        JPanel row = new JPanel(layout)
        {
            @Override
            public Dimension getPreferredSize()
            {
                return new Dimension(0, height);
            }

            @Override
            public Dimension getMinimumSize()
            {
                return new Dimension(0, height);
            }

            @Override
            public Dimension getMaximumSize()
            {
                return new Dimension(Integer.MAX_VALUE, height);
            }
        };
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    /**
     * A label that never asks for width, so its layout slot decides it and Swing truncates text that
     * doesn't fit with "...". Set its text with {@link #setTruncated} so the full text is the tooltip.
     */
    public static JLabel truncating()
    {
        return new JLabel()
        {
            @Override
            public Dimension getPreferredSize()
            {
                return new Dimension(0, super.getPreferredSize().height);
            }

            @Override
            public Dimension getMinimumSize()
            {
                return getPreferredSize();
            }
        };
    }

    /** Sets a {@link #truncating} label's text, with the full text as its tooltip. */
    public static void setTruncated(JLabel label, String text)
    {
        boolean blank = text == null || text.isEmpty();
        // A space keeps the row's height when there is nothing to show.
        label.setText(blank ? " " : text);
        label.setToolTipText(blank ? null : text);
    }

    /** A label exactly as wide as {@code sample} in {@code font}, so changing digits move nothing. */
    public static JLabel fixedWidth(Font font, String sample, int align)
    {
        JLabel label = new JLabel();
        label.setFont(font);
        label.setHorizontalAlignment(align);
        FontMetrics metrics = label.getFontMetrics(font);
        Dimension size = new Dimension(metrics.stringWidth(sample) + FIT_SLACK, metrics.getHeight());
        label.setPreferredSize(size);
        label.setMinimumSize(size);
        label.setMaximumSize(size);
        return label;
    }

    /** Two labels on one line, one at each end. */
    public static JComponent line(JLabel left, JLabel right)
    {
        JPanel line = new JPanel(new BorderLayout());
        line.setOpaque(false);
        line.add(left, BorderLayout.WEST);
        line.add(right, BorderLayout.EAST);
        return line;
    }

    /** A small grey label. */
    public static JLabel small(String text)
    {
        JLabel label = new JLabel(text);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        return label;
    }

    /** An empty list's message. */
    public static JLabel empty(String text)
    {
        JLabel label = small(text);
        label.setBorder(BorderFactory.createEmptyBorder(EMPTY_PAD, 0, EMPTY_PAD, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setHorizontalAlignment(SwingConstants.LEFT);
        return label;
    }

    /** A compact button in the small font that never takes keyboard focus. */
    public static JButton smallButton(String text)
    {
        JButton button = new JButton(text);
        button.setFont(FontManager.getRunescapeSmallFont());
        button.setFocusable(false);
        button.setMargin(BUTTON_MARGIN);
        return button;
    }

    /** Caps a component's maximum height at its preferred height, so a vertical box doesn't stretch it. */
    public static JComponent capHeight(JComponent component)
    {
        JPanel holder = new JPanel(new BorderLayout())
        {
            @Override
            public Dimension getMaximumSize()
            {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        holder.setOpaque(false);
        holder.setAlignmentX(Component.LEFT_ALIGNMENT);
        holder.add(component, BorderLayout.CENTER);
        return holder;
    }

    /**
     * Adds a mouse listener and the hand cursor to a component and everything inside it, since a
     * label with a tooltip takes the clicks meant for the row under it.
     */
    public static void listenDeep(Component component, MouseListener listener)
    {
        component.addMouseListener(listener);
        component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (component instanceof Container)
        {
            for (Component child : ((Container) component).getComponents())
            {
                listenDeep(child, listener);
            }
        }
    }

    /** {@code base} moved {@code amount} (0..1) of the way towards {@code tint}. */
    public static Color blend(Color base, Color tint, float amount)
    {
        return new Color(
            Math.round(base.getRed() + (tint.getRed() - base.getRed()) * amount),
            Math.round(base.getGreen() + (tint.getGreen() - base.getGreen()) * amount),
            Math.round(base.getBlue() + (tint.getBlue() - base.getBlue()) * amount));
    }

    /** {@code #rrggbb}, for HTML labels. */
    public static String hex(Color color)
    {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /** A small filled circle, for status labels. */
    public static final class Dot implements Icon
    {
        /** The icon's box and the circle inside it, in pixels. */
        private static final int WIDTH = 10;
        private static final int HEIGHT = 11;
        private static final int DIAMETER = 7;
        private static final int INSET_X = 1;
        private static final int INSET_Y = 2;

        private final Color color;

        public Dot(Color color)
        {
            this.color = color;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y)
        {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x + INSET_X, y + INSET_Y, DIAMETER, DIAMETER);
            g2.dispose();
        }

        @Override
        public int getIconWidth()
        {
            return WIDTH;
        }

        @Override
        public int getIconHeight()
        {
            return HEIGHT;
        }
    }

    /** A scroll view's content: as wide as the viewport, as tall as what it holds. */
    public static final class ScrollingDisplay extends JPanel implements Scrollable
    {
        /** Pixels one scroll-wheel notch moves. */
        public static final int UNIT_INCREMENT = 16;

        public ScrollingDisplay()
        {
            super(new BorderLayout());
            setBackground(ColorScheme.DARK_GRAY_COLOR);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize()
        {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
        {
            return UNIT_INCREMENT;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
        {
            // A page, keeping one notch of the previous page in view.
            return Math.max(UNIT_INCREMENT, visibleRect.height - UNIT_INCREMENT);
        }

        @Override
        public boolean getScrollableTracksViewportWidth()
        {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight()
        {
            return false;
        }
    }
}
