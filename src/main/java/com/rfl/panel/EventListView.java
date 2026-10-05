package com.rfl.panel;

import sh.yumekui.toolkit.text.Html;
import sh.yumekui.toolkit.swing.ConfirmHeader;
import sh.yumekui.toolkit.swing.SidebarWidgets;
import sh.yumekui.toolkit.swing.ToggleSelection;

import java.awt.Color;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;

/**
 * One list of the session's events (collisions or incompletes): an inline-confirmed Clear over the
 * rows. A row is a click target: hand cursor, hover, and a selected state in the list's accent (a
 * left bar and a tint, with the same border widths so nothing moves). Clearing only empties the
 * panel's list and count for this session; the day files on disk are never touched.
 *
 * <p>Swing EDT only.
 *
 * @param <R> the panel model's row type
 */
final class EventListView<R>
{
    /** The divider under each row, the selected row's accent bar, and the row padding. */
    private static final int DIVIDER = 1;
    private static final int SELECTED_BAR = 3;
    private static final int PAD_Y = 4;
    private static final int PAD_LEFT = 3;
    private static final int PAD_RIGHT = 6;

    private final JPanel root = SidebarWidgets.vertical();
    private final JPanel list = SidebarWidgets.vertical();
    private final ConfirmHeader header;
    private final String emptyText;
    private final Color accent;
    private final BiConsumer<JPanel, R> content;
    private final ToggleSelection selection;
    private final Consumer<Object> onClick;
    private List<R> rows = List.of();
    private int count;

    /**
     * @param noun "collisions" or "incompletes", for the Clear question
     * @param hint the line beside Clear
     * @param content fills a row's panel for one value
     * @param selection which row is selected, shared with the panel
     * @param onClick runs with a row's value when it is clicked
     * @param onClear runs once a Clear is confirmed
     */
    EventListView(String noun, String hint, String emptyText, Color accent, BiConsumer<JPanel, R> content,
        ToggleSelection selection, Consumer<Object> onClick, Runnable onClear)
    {
        this.emptyText = emptyText;
        this.accent = accent;
        this.content = content;
        this.selection = selection;
        this.onClick = onClick;
        header = new ConfirmHeader(SidebarWidgets.small(hint), "Clear",
            "Clears this list and its count in the panel. Saved files are kept.",
            () -> PanelStyle.wrap(Html.escape(PanelModel.clearQuestion(count, noun))), PanelStyle.ALERT, onClear);
        root.add(header.component());
        root.add(list);
    }

    JComponent root()
    {
        return root;
    }

    /** The session's total, which decides whether there is anything to clear. */
    void setCount(int count)
    {
        this.count = count;
        header.setAvailable(count > 0);
    }

    /** Rebuilds the rows. */
    void show(List<R> rows)
    {
        this.rows = rows;
        list.removeAll();
        if (rows.isEmpty())
        {
            list.add(SidebarWidgets.empty(emptyText));
        }
        for (R value : rows)
        {
            JPanel row = SidebarWidgets.row();
            content.accept(row, value);
            clickable(row, value);
            list.add(row);
        }
    }

    /** Rebuilds the rows for a changed selection and lays them out again. */
    void restyle()
    {
        show(rows);
        list.revalidate();
        list.repaint();
    }

    private void clickable(JPanel row, R value)
    {
        boolean selected = selection.isSelected(value);
        Color base = selected ? SidebarWidgets.blend(ColorScheme.DARKER_GRAY_COLOR, accent, PanelStyle.SELECTED_TINT)
            : ColorScheme.DARKER_GRAY_COLOR;
        row.setBackground(base);
        row.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, DIVIDER, 0, ColorScheme.DARK_GRAY_COLOR),
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, SELECTED_BAR, 0, 0, selected ? accent : base),
                BorderFactory.createEmptyBorder(PAD_Y, PAD_LEFT, PAD_Y, PAD_RIGHT))));
        SidebarWidgets.listenDeep(row, new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent event)
            {
                onClick.accept(value);
            }

            @Override
            public void mouseEntered(MouseEvent event)
            {
                row.setBackground(selected ? base : ColorScheme.DARK_GRAY_HOVER_COLOR);
            }

            @Override
            public void mouseExited(MouseEvent event)
            {
                row.setBackground(base);
            }
        });
    }
}
