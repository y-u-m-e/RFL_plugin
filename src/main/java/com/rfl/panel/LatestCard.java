package com.rfl.panel;

import sh.yumekui.toolkit.swing.SidebarWidgets;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The latest collision or incomplete of the session, as a highlighted card in that kind's accent,
 * or a grey placeholder before the first one.
 *
 * <p>Swing EDT only.
 */
final class LatestCard
{
    /** Gap between the kind/time line and the body, in pixels. */
    private static final int BODY_GAP = 3;
    /** The card's left accent bar and padding. */
    private static final int ACCENT_WIDTH = 4;
    private static final int PAD = 8;

    private final JPanel card = new JPanel(new BorderLayout(0, BODY_GAP));
    private final JLabel kind = new JLabel();
    private final JLabel time = new JLabel();
    private final JLabel body = new JLabel();

    LatestCard()
    {
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        kind.setFont(FontManager.getRunescapeBoldFont());
        time.setFont(FontManager.getRunescapeSmallFont());
        time.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        head.add(kind, BorderLayout.WEST);
        head.add(time, BorderLayout.EAST);
        body.setFont(FontManager.getRunescapeFont());
        body.setForeground(Color.WHITE);
        card.add(head, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    /** The card, height-capped for a vertical box. */
    JComponent component()
    {
        return SidebarWidgets.capHeight(card);
    }

    /** Shows {@code event}, or the placeholder when it is null. */
    void show(PanelModel.LatestEvent event)
    {
        if (event == null)
        {
            card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            card.setBorder(border(ColorScheme.MEDIUM_GRAY_COLOR));
            kind.setText("LATEST");
            kind.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            time.setText("");
            body.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            body.setText(PanelStyle.wrap("No collisions or incompletes yet this session."));
            return;
        }
        Color accent = event.getKind() == PanelModel.Kind.INCOMPLETE ? PanelStyle.INCOMPLETE : PanelStyle.COLLISION;
        card.setBackground(SidebarWidgets.blend(ColorScheme.DARKER_GRAY_COLOR, accent, PanelStyle.CARD_TINT));
        card.setBorder(border(accent));
        kind.setText(event.getKind().label.toUpperCase());
        kind.setForeground(accent);
        time.setText(event.getTime());
        body.setForeground(Color.WHITE);
        body.setText(PanelStyle.wrap(PanelStyle.arrows(event.getBodyHtml())));
    }

    private static javax.swing.border.Border border(Color accent)
    {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, ACCENT_WIDTH, 0, 0, accent),
            BorderFactory.createEmptyBorder(PAD, PAD, PAD, PAD));
    }
}
