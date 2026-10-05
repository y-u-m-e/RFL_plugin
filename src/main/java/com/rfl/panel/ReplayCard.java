package com.rfl.panel;

import com.rfl.replay.ReplayState;
import sh.yumekui.toolkit.swing.SidebarWidgets;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The replay card: row 1 the state and a fixed-width value (elapsed, percent or size), row 2 the
 * file name (or error, or idle hint) truncated with an ellipsis, row 3 the size and model count, the
 * saving bar or Open folder. Every row has a fixed height and the border never changes width, so
 * the card is the same height in every state and nothing below it ever moves.
 *
 * <p>Swing EDT only.
 */
final class ReplayCard
{
    /** The saving bar's height in pixels, centred in row 3; its width follows the card. */
    private static final int BAR_HEIGHT = 8;
    private static final int BAR_PREFERRED_WIDTH = 10;
    /** Row 1's gap between title and value, and the gaps between rows. */
    private static final int TITLE_GAP = 4;
    private static final int GAP_AFTER_TITLE = 2;
    private static final int GAP_AFTER_DETAIL = 4;
    /** The card's left accent bar and padding. */
    private static final int ACCENT_WIDTH = 4;
    private static final int PAD_Y = 6;
    private static final int PAD_X = 8;

    /** Row 3's views, one per state ({@link #extraCard}). */
    private static final String BLANK = "blank";
    private static final String STATS = "stats";
    private static final String BAR = "bar";
    private static final String OPEN = "open";

    private final JPanel card = new JPanel();
    private final JLabel title = SidebarWidgets.truncating();
    private final JLabel value;
    private final JLabel detail = SidebarWidgets.truncating();
    private final CardLayout extraCards = new CardLayout();
    private final JPanel extra = new JPanel(extraCards);
    private final JLabel size;
    private final JLabel models;
    private final JProgressBar bar = new JProgressBar(0, PanelModel.PERCENT);
    private final JButton openFolder = new JButton("Open folder");

    /** @param onOpenFolder runs on the EDT when Open folder is clicked */
    ReplayCard(Runnable onOpenFolder)
    {
        Font bold = FontManager.getRunescapeBoldFont();
        Font small = FontManager.getRunescapeSmallFont();
        value = SidebarWidgets.fixedWidth(bold, PanelModel.VALUE_SAMPLE, SwingConstants.RIGHT);
        size = SidebarWidgets.fixedWidth(small, PanelModel.SIZE_SAMPLE, SwingConstants.LEFT);
        models = SidebarWidgets.fixedWidth(small, PanelModel.MODELS_SAMPLE, SwingConstants.RIGHT);
        title.setFont(bold);
        detail.setFont(small);
        detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        size.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        models.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        bar.setStringPainted(false);
        bar.setBorderPainted(false);
        bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
        bar.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
        bar.setPreferredSize(new Dimension(BAR_PREFERRED_WIDTH, BAR_HEIGHT));

        openFolder.setFocusable(false);
        openFolder.setFont(small);
        openFolder.setMargin(SidebarWidgets.BUTTON_MARGIN);
        openFolder.setToolTipText("Opens the RFL folder; replays are in rfl/replays.");
        openFolder.addActionListener(e -> onOpenFolder.run());

        int row1 = Math.max(lineHeight(bold), new SidebarWidgets.Dot(PanelStyle.OK).getIconHeight());
        int row2 = lineHeight(small);
        int row3 = Math.max(openFolder.getPreferredSize().height, lineHeight(small));

        JPanel first = SidebarWidgets.fixedRow(new BorderLayout(TITLE_GAP, 0), row1);
        first.add(title, BorderLayout.CENTER);
        first.add(value, BorderLayout.EAST);

        JPanel second = SidebarWidgets.fixedRow(new BorderLayout(), row2);
        second.add(detail, BorderLayout.CENTER);

        JPanel third = SidebarWidgets.fixedRow(new BorderLayout(), row3);
        third.add(extraViews(row3), BorderLayout.CENTER);

        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.add(first);
        card.add(Box.createVerticalStrut(GAP_AFTER_TITLE));
        card.add(second);
        card.add(Box.createVerticalStrut(GAP_AFTER_DETAIL));
        card.add(third);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    /** Row 3's alternatives: blank, size and model count, the saving bar, or Open folder. */
    private JPanel extraViews(int rowHeight)
    {
        JPanel stats = new JPanel(new BorderLayout());
        stats.setOpaque(false);
        stats.add(size, BorderLayout.WEST);
        stats.add(models, BorderLayout.EAST);
        JPanel barRow = new JPanel(new BorderLayout());
        barRow.setOpaque(false);
        int above = Math.max(0, (rowHeight - BAR_HEIGHT) / 2);
        barRow.setBorder(BorderFactory.createEmptyBorder(above, 0, rowHeight - BAR_HEIGHT - above, 0));
        barRow.add(bar, BorderLayout.CENTER);
        JPanel open = new JPanel(new BorderLayout());
        open.setOpaque(false);
        open.add(openFolder, BorderLayout.WEST);
        JPanel blank = new JPanel();
        blank.setOpaque(false);
        extra.setOpaque(false);
        extra.add(blank, BLANK);
        extra.add(stats, STATS);
        extra.add(barRow, BAR);
        extra.add(open, OPEN);
        return extra;
    }

    private int lineHeight(Font font)
    {
        return card.getFontMetrics(font).getHeight();
    }

    /** The card, height-capped for a vertical box. */
    JComponent component()
    {
        return SidebarWidgets.capHeight(card);
    }

    /** The card's height as laid out now. */
    int preferredHeight()
    {
        return card.getPreferredSize().height;
    }

    /** Fills the card's fixed rows. Plain text only, so long text truncates with "...". */
    void show(PanelModel.ReplayStrip strip)
    {
        Color accent = accent(strip.getState());
        card.setBackground(SidebarWidgets.blend(ColorScheme.DARKER_GRAY_COLOR, accent, PanelStyle.CARD_TINT));
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, ACCENT_WIDTH, 0, 0, accent),
            BorderFactory.createEmptyBorder(PAD_Y, PAD_X, PAD_Y, PAD_X)));
        card.setToolTipText(strip.getText());
        Color titleColor = strip.getState() == ReplayState.SAVED ? PanelStyle.OK
            : strip.getState() == ReplayState.IDLE ? ColorScheme.LIGHT_GRAY_COLOR : accent;
        title.setForeground(titleColor);
        title.setIcon(new SidebarWidgets.Dot(strip.getState() == ReplayState.RECORDING ? accent
            : ColorScheme.MEDIUM_GRAY_COLOR));
        SidebarWidgets.setTruncated(title, strip.getTitle());
        value.setForeground(titleColor);
        value.setText(strip.getValue());
        SidebarWidgets.setTruncated(detail, strip.getDetail());
        size.setText(strip.getSize());
        models.setText(strip.getModels());
        bar.setForeground(accent);
        bar.setValue(strip.getPercent());
        extraCards.show(extra, extraCard(strip.getState()));
    }

    /** Row 3's content per state. */
    private static String extraCard(ReplayState state)
    {
        switch (state)
        {
            case RECORDING:
                return STATS;
            case SAVING:
                return BAR;
            case SAVED:
                return OPEN;
            default:
                return BLANK;
        }
    }

    private static Color accent(ReplayState state)
    {
        switch (state)
        {
            case IDLE:
                return ColorScheme.MEDIUM_GRAY_COLOR;
            case SAVING:
                return ColorScheme.PROGRESS_INPROGRESS_COLOR;
            case SAVED:
                return PanelStyle.OK;
            case ERROR:
                return ColorScheme.PROGRESS_ERROR_COLOR;
            default:
                return PanelStyle.ALERT.brighter();
        }
    }
}
