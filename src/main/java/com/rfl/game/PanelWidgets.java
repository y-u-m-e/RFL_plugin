package com.rfl.game;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Small Swing building blocks for {@link GamePanel}: rows, labels, buttons and painted icons in
 * the sidebar's fonts and colours. Every label built here treats server text as plain text
 * ({@link #plain}, {@link #esc}), since game and player names come from other players. EDT only.
 */
final class PanelWidgets
{
    private PanelWidgets()
    {
    }

    /** Left, middle (stretched) and right on one row; a null right slot is left out. */
    static JPanel line(final Component left, final Component middle, final Component right)
    {
        final JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 0, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = left instanceof JLabel ? 1 : 0;
        p.add(left, c);
        c.weightx = left instanceof JLabel ? 0 : 1;
        p.add(middle, c);
        if (right != null)
        {
            c.weightx = 0;
            c.insets = new Insets(0, 0, 0, 0);
            p.add(right, c);
        }
        return p;
    }

    /** Black and white diagonal stripes, the referees' colours; tiles seamlessly at width 4. */
    static Icon stripes(final int w, final int h, final boolean outline)
    {
        return new Icon()
        {
            @Override
            public void paintIcon(final Component c, final Graphics g, final int x, final int y)
            {
                for (int px = 0; px < w; px++)
                {
                    for (int py = 0; py < h; py++)
                    {
                        g.setColor((px + py) % 4 < 2 ? Color.BLACK : Color.WHITE);
                        g.fillRect(x + px, y + py, 1, 1);
                    }
                }
                if (outline)
                {
                    g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
                    g.drawRect(x, y, w - 1, h - 1);
                }
            }

            @Override
            public int getIconWidth()
            {
                return w;
            }

            @Override
            public int getIconHeight()
            {
                return h;
            }
        };
    }

    /** A painted colour square with a light outline — renders the same under any look and feel. */
    static Icon swatch(final Color color, final int w, final int h)
    {
        return new Icon()
        {
            @Override
            public void paintIcon(final Component c, final Graphics g, final int x, final int y)
            {
                g.setColor(color);
                g.fillRect(x, y, w, h);
                g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
                g.drawRect(x, y, w - 1, h - 1);
            }

            @Override
            public int getIconWidth()
            {
                return w;
            }

            @Override
            public int getIconHeight()
            {
                return h;
            }
        };
    }

    /** Wraps a component so BoxLayout stretches it to the panel width with a small gap below. */
    static JPanel row(final Component c)
    {
        final JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        p.add(c, BorderLayout.CENTER);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    static JPanel title(final String s)
    {
        final JLabel l = label(s, Color.WHITE);
        l.setFont(FontManager.getRunescapeBoldFont());
        return row(l);
    }

    static JPanel section(final String s)
    {
        final JLabel l = label(s, ColorScheme.BRAND_ORANGE);
        l.setFont(FontManager.getRunescapeBoldFont());
        l.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        return row(l);
    }

    /** A wrapping line of plain text (escaped into HTML, width-capped to wrap in the ~225 px panel). */
    static JPanel text(final String s, final Color color)
    {
        final JLabel l = new JLabel("<html><div style='width:190px'>" + esc(s) + "</div></html>");
        l.setForeground(color);
        l.setFont(FontManager.getRunescapeSmallFont());
        return row(l);
    }

    /** Plain-text label; server-supplied text can never switch it into HTML (see {@link #plain}). */
    static JLabel label(final String s, final Color color)
    {
        final JLabel l = new JLabel(plain(s));
        l.setForeground(color);
        l.setFont(FontManager.getRunescapeSmallFont());
        return l;
    }

    static JButton button(final String s, final Runnable onClick)
    {
        final JButton b = new JButton(s);
        b.setFont(FontManager.getRunescapeSmallFont());
        b.setFocusable(false);
        b.addActionListener(e -> onClick.run());
        return b;
    }

    /**
     * Swing renders any label/combo text starting with {@code <html>} as HTML (which can load
     * remote images). Names come from other players via the API, so a leading space defuses it.
     */
    static String plain(final String s)
    {
        if (s == null)
        {
            return "";
        }
        return s.regionMatches(true, 0, "<html", 0, 5) ? " " + s : s;
    }

    static String esc(final String s)
    {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
