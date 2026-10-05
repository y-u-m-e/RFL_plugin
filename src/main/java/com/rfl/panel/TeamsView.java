package com.rfl.panel;

import sh.yumekui.toolkit.text.Html;
import com.rfl.teams.Teams;
import sh.yumekui.toolkit.swing.ConfirmHeader;
import sh.yumekui.toolkit.swing.SidebarWidgets;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.function.BiConsumer;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The Teams view: everyone in the house plus everyone assigned (absent ones dimmed), each with a
 * compact A / B / - selector, and Clear teams behind an inline confirmation. Assignments stay until
 * changed here, by right-click in game, or by Clear teams.
 *
 * <p>Swing EDT only.
 */
final class TeamsView
{
    /** Gap between a row's three selector buttons, and each button's side margin, in pixels. */
    private static final int PICK_GAP = 2;
    private static final Insets PICK_MARGIN = new Insets(0, 4, 0, 4);
    /** The selector's label for unassigned. */
    private static final String UNASSIGNED = "-";

    private final JPanel root = SidebarWidgets.vertical();
    private final JPanel list = SidebarWidgets.vertical();
    private final ConfirmHeader header;
    private final BiConsumer<String, Teams.Team> assign;

    /**
     * @param assign runs when a row's A / B / - is clicked (null team: unassign)
     * @param onClear runs once Clear teams is confirmed
     */
    TeamsView(BiConsumer<String, Teams.Team> assign, Runnable onClear)
    {
        this.assign = assign;
        JLabel hint = SidebarWidgets.small("Only A against B counts.");
        hint.setToolTipText("Same-team pairs and unassigned players never count. Right-click a player in "
            + "the house for RFL: Team A / Team B / Unassign.");
        header = new ConfirmHeader(hint, "Clear teams", "Unassigns everyone, after you confirm.",
            () -> PanelStyle.wrap(Html.escape(PanelModel.CLEAR_TEAMS_QUESTION)), PanelStyle.ALERT, onClear);
        root.add(header.component());
        root.add(list);
    }

    JComponent root()
    {
        return root;
    }

    /** Rebuilds the rows for a new state. */
    void show(PanelModel.TeamsState state)
    {
        header.setAvailable(state.getTeamA() + state.getTeamB() > 0);
        list.removeAll();
        if (state.getRows().isEmpty())
        {
            list.add(SidebarWidgets.empty("Nobody here yet. Players show up once you're in a house."));
        }
        for (PanelModel.TeamRow row : state.getRows())
        {
            list.add(row(row));
        }
        list.revalidate();
        list.repaint();
    }

    private JPanel row(PanelModel.TeamRow teamRow)
    {
        JPanel row = SidebarWidgets.row();
        JLabel name = SidebarWidgets.truncating();
        name.setFont(FontManager.getRunescapeFont());
        name.setForeground(!teamRow.isPresent() ? ColorScheme.MEDIUM_GRAY_COLOR
            : teamRow.getTeam() == null ? Color.WHITE : teamRow.getTeam().color());
        SidebarWidgets.setTruncated(name, teamRow.getName());
        if (!teamRow.isPresent())
        {
            name.setToolTipText(teamRow.getName() + " (not in the house)");
        }
        JPanel picks = new JPanel(new GridLayout(1, 3, PICK_GAP, 0));
        picks.setOpaque(false);
        picks.add(pick(teamRow, Teams.Team.A));
        picks.add(pick(teamRow, Teams.Team.B));
        picks.add(pick(teamRow, null));
        row.add(name, BorderLayout.CENTER);
        row.add(picks, BorderLayout.EAST);
        return row;
    }

    /** One of the row's A / B / - buttons; the current choice is bold and in its team colour. */
    private JButton pick(PanelModel.TeamRow row, Teams.Team team)
    {
        JButton button = SidebarWidgets.smallButton(team == null ? UNASSIGNED : team.name());
        boolean current = row.getTeam() == team;
        button.setMargin(PICK_MARGIN);
        if (current)
        {
            button.setFont(FontManager.getRunescapeBoldFont());
            button.setForeground(team == null ? Color.WHITE : team.color());
        }
        else
        {
            button.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        }
        button.setToolTipText(team == null ? "Unassigned" : team.label());
        button.addActionListener(e ->
        {
            if (!current)
            {
                assign.accept(row.getName(), team);
            }
        });
        return button;
    }
}
