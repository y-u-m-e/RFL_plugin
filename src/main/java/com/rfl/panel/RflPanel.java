package com.rfl.panel;

import sh.yumekui.toolkit.swing.CopyFeedbackLabel;
import sh.yumekui.toolkit.swing.SelectableCard;
import sh.yumekui.toolkit.text.Html;
import com.rfl.overlay.EventTileOverlay;
import com.rfl.overlay.TeamTileOverlay;
import com.rfl.teams.Teams;
import sh.yumekui.toolkit.swing.SidebarWidgets;
import sh.yumekui.toolkit.swing.ToggleSelection;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.ZoneId;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;

/**
 * The "RFL" sidebar panel. Top to bottom: a status strip (house, a brief "Saved collision" / "Saved
 * plugin list" tick); the replay card ({@link ReplayCard}), which always holds its place; the
 * session's collision and incomplete counts as two cards that also pick the list shown below
 * ({@link SelectableCard}), and a full-width Teams card; the latest event ({@link LatestCard}); the
 * selected list ({@link EventListView} or {@link TeamsView}); and a footer with Copy plugin history,
 * the record button and Open folder, each a full-width row so no label is cut off at sidebar width.
 * It renders a {@link PanelModel} and formats nothing itself.
 *
 * <p>Threads: Swing EDT only. Models arrive through {@link #update}.
 */
public final class RflPanel extends PluginPanel
{
    /** The panel's own border on each side; the usable width is {@link #PANEL_WIDTH} minus twice this. */
    static final int SIDE = 8;
    /** How long a Copy plugin history result stays up. */
    private static final int COPY_MESSAGE_MS = 4000;
    /** Space between the panel's sections, and the smaller one under the latest card, in pixels. */
    private static final int SECTION_GAP = 6;
    private static final int SMALL_GAP = 4;
    /** Space between the two count cards, and between footer rows. */
    private static final int CARD_GAP = 6;
    private static final int FOOTER_GAP = 4;
    private static final int COPY_RESULT_GAP = 2;
    /** The team hint wraps a little wider than a card's text, since it has no card padding. */
    private static final int HINT_EXTRA_WIDTH = 20;
    private static final int HINT_PAD_TOP = 4;
    /** The sidebar toolbar's icon size, in pixels. */
    private static final int ICON_SIZE = 16;

    /** Which list the panel shows; picked by clicking a count card. */
    public enum View
    {
        COLLISIONS,
        INCOMPLETES,
        TEAMS
    }

    private final JLabel status = new JLabel();
    private final JLabel savedTick = new JLabel();
    private final JButton recordButton = new JButton();
    /** The record button's last {@link #setRecording} armed flag, and whether the mouse is over it. */
    private boolean recordArmed;
    private boolean recordHover;
    private final ReplayCard replayCard;
    private final SelectableCard collisionsTile = new SelectableCard("COLLISIONS", PanelStyle.COLLISION, false,
        () -> selectView(View.COLLISIONS));
    private final SelectableCard incompletesTile = new SelectableCard("INCOMPLETES", PanelStyle.INCOMPLETE, false,
        () -> selectView(View.INCOMPLETES));
    /** Three side by side don't fit the sidebar with "INCOMPLETES" legible, so Teams is a full-width card below. */
    private final SelectableCard teamsTile = new SelectableCard("TEAMS", ColorScheme.LIGHT_GRAY_COLOR, true,
        () -> selectView(View.TEAMS));
    private final JLabel teamHint = new JLabel();
    private final LatestCard latestCard = new LatestCard();

    /** The collision or incomplete row whose tile is highlighted in the scene. */
    private final ToggleSelection selection = new ToggleSelection();
    private final EventListView<PanelModel.CollisionRow> collisionsView;
    private final EventListView<PanelModel.IncompleteRow> incompletesView;
    private final TeamsView teamsView;
    private final SidebarWidgets.ScrollingDisplay display = new SidebarWidgets.ScrollingDisplay();
    private View view;
    /** The panel is the open sidebar tab (between onActivate and onDeactivate). */
    private boolean active;
    /** Told on the EDT whether the Teams view is on screen, for {@link TeamTileOverlay}. */
    private Consumer<Boolean> teamsShowing = showing -> { };
    /** Told on the EDT which tile to highlight (null: none), for {@link EventTileOverlay}. */
    private Consumer<EventTileOverlay.EventTile> eventTile = tile -> { };

    private final CopyFeedbackLabel copyResult = new CopyFeedbackLabel(COPY_MESSAGE_MS);

    private PanelModel shown;

    /**
     * @param openFolder runs on the EDT when Open folder is clicked
     * @param copyHistory runs on the EDT when Copy plugin history is clicked
     * @param toggleRecording runs on the EDT when the record button is clicked
     * @param clearCollisions runs on the EDT once a Clear on the Collisions view is confirmed
     * @param clearIncompletes runs on the EDT once a Clear on the Incompletes view is confirmed
     * @param assignTeam runs on the EDT when a Teams row's A / B / - is clicked (null team: unassign)
     * @param clearTeams runs on the EDT once Clear teams is confirmed
     */
    public RflPanel(Runnable openFolder, Runnable copyHistory, Runnable toggleRecording, Runnable clearCollisions,
        Runnable clearIncompletes, BiConsumer<String, Teams.Team> assignTeam, Runnable clearTeams)
    {
        super(false);
        setLayout(new BorderLayout(0, SECTION_GAP));
        setBorder(BorderFactory.createEmptyBorder(SIDE, SIDE, SIDE, SIDE));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        replayCard = new ReplayCard(openFolder);
        collisionsView = new EventListView<>("collisions", "Newest first. Underlined: had the ball.",
            "No collisions this session.", PanelStyle.COLLISION, RflPanel::fillCollisionRow, selection,
            this::clickRow, clearCollisions);
        incompletesView = new EventListView<>("incompletes", "Newest first.", "No incompletes this session.",
            PanelStyle.INCOMPLETE, RflPanel::fillIncompleteRow, selection, this::clickRow, clearIncompletes);
        teamsView = new TeamsView(assignTeam, clearTeams);

        add(top(), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(display, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.getVerticalScrollBar().setUnitIncrement(SidebarWidgets.ScrollingDisplay.UNIT_INCREMENT);
        add(scroll, BorderLayout.CENTER);
        add(footer(openFolder, copyHistory, toggleRecording), BorderLayout.SOUTH);

        selectView(View.COLLISIONS);
        update(PanelModel.of(false, false, 0, 0, List.of(), List.of(), null,
            PanelModel.replayStrip(null, false, 0L), null, ZoneId.systemDefault()));
    }

    // ---- Layout ----

    /** Everything above the list: status, replay card, count cards, team hint, latest event. */
    private JComponent top()
    {
        JPanel top = SidebarWidgets.vertical();
        top.add(statusStrip());
        top.add(Box.createVerticalStrut(SECTION_GAP));
        top.add(replayCard.component());
        top.add(Box.createVerticalStrut(SECTION_GAP));
        top.add(countCards());
        top.add(Box.createVerticalStrut(SECTION_GAP));
        top.add(SidebarWidgets.capHeight(teamsTile));
        top.add(teamHint());
        top.add(Box.createVerticalStrut(SECTION_GAP));
        top.add(latestCard.component());
        top.add(Box.createVerticalStrut(SMALL_GAP));
        return top;
    }

    private JComponent statusStrip()
    {
        JPanel strip = new JPanel(new BorderLayout());
        strip.setOpaque(false);
        strip.setAlignmentX(LEFT_ALIGNMENT);
        status.setFont(FontManager.getRunescapeBoldFont());
        savedTick.setFont(FontManager.getRunescapeSmallFont());
        savedTick.setForeground(PanelStyle.OK);
        savedTick.setIcon(new SidebarWidgets.Dot(PanelStyle.OK));
        savedTick.setToolTipText("A line was just written to the RFL folder.");
        strip.add(status, BorderLayout.WEST);
        strip.add(savedTick, BorderLayout.EAST);
        return SidebarWidgets.capHeight(strip);
    }

    private JComponent countCards()
    {
        JPanel row = new JPanel(new GridLayout(1, 2, CARD_GAP, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(collisionsTile);
        row.add(incompletesTile);
        return SidebarWidgets.capHeight(row);
    }

    /** The "assign teams" hint, shown only while detection can't count anything. */
    private JComponent teamHint()
    {
        // Wrapped rather than cut: at sidebar width the sentence needs two short lines.
        teamHint.setText(PanelStyle.wrap(Html.escape(PanelModel.TEAM_HINT), PanelStyle.WRAP + HINT_EXTRA_WIDTH));
        teamHint.setToolTipText("Only Team A against Team B counts. Use the Teams card, or right-click a player "
            + "in the house.");
        teamHint.setFont(FontManager.getRunescapeSmallFont());
        teamHint.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
        teamHint.setBorder(BorderFactory.createEmptyBorder(HINT_PAD_TOP, 0, 0, 0));
        teamHint.setVisible(false);
        return SidebarWidgets.capHeight(teamHint);
    }

    /** Copy plugin history with its result line, then the record button, then Open folder: one per row. */
    private JComponent footer(Runnable openFolder, Runnable copyHistory, Runnable toggleRecording)
    {
        JButton copy = new JButton(PanelModel.COPY_BUTTON);
        copy.setToolTipText("Copies today's plugin history to the clipboard, laid out for Discord: "
            + "each house visit's plugin list and the plugins turned on (+) or off (-) during it.");
        copy.setFocusable(false);
        copy.addActionListener(e -> copyHistory.run());

        recordButton.setFocusable(false);
        recordButton.addActionListener(e -> toggleRecording.run());
        recordButton.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseEntered(MouseEvent e)
            {
                recordHover = true;
                paintRecordButton();
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                recordHover = false;
                paintRecordButton();
            }
        });
        // Whoever disables the button, its colour and cursor follow.
        recordButton.addPropertyChangeListener("enabled", e -> paintRecordButton());
        setRecording(false, false);

        JButton open = new JButton("Open folder");
        open.setToolTipText("Opens the RFL folder: collisions, plugin history and replays.");
        open.setFocusable(false);
        open.addActionListener(e -> openFolder.run());

        JPanel footer = SidebarWidgets.vertical();
        footer.add(SidebarWidgets.capHeight(copy));
        footer.add(Box.createVerticalStrut(COPY_RESULT_GAP));
        footer.add(SidebarWidgets.capHeight(copyResult.component()));
        footer.add(Box.createVerticalStrut(FOOTER_GAP));
        footer.add(SidebarWidgets.capHeight(recordButton));
        footer.add(Box.createVerticalStrut(FOOTER_GAP));
        footer.add(SidebarWidgets.capHeight(open));
        return footer;
    }

    /** A collision row: time and overlap, then the pair with a lone ball holder marked. */
    private static void fillCollisionRow(JPanel row, PanelModel.CollisionRow collision)
    {
        row.add(SidebarWidgets.line(SidebarWidgets.small(collision.getTime()),
            SidebarWidgets.small("overlap " + collision.getOverlap())), BorderLayout.NORTH);
        JLabel pair = new JLabel("<html>" + PanelStyle.arrows(PanelModel.pairHtml(collision)) + "</html>");
        pair.setFont(FontManager.getRunescapeFont());
        pair.setForeground(Color.WHITE);
        pair.setToolTipText(PanelModel.ball(collision) + ". Click to highlight its tile.");
        row.add(pair, BorderLayout.CENTER);
    }

    /** An incomplete row: time, the receiver, who they were in contact with. */
    private static void fillIncompleteRow(JPanel row, PanelModel.IncompleteRow incomplete)
    {
        row.add(SidebarWidgets.small(incomplete.getTime()), BorderLayout.NORTH);
        JLabel receiver = new JLabel(incomplete.getReceiver());
        receiver.setFont(FontManager.getRunescapeBoldFont());
        receiver.setForeground(PanelStyle.INCOMPLETE);
        row.add(receiver, BorderLayout.CENTER);
        JLabel contacts = new JLabel(PanelStyle.wrap(Html.escape(incomplete.getContacts())));
        contacts.setFont(FontManager.getRunescapeSmallFont());
        contacts.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        row.add(contacts, BorderLayout.SOUTH);
    }

    // ---- Views ----

    /** EDT: shows a view's list below the cards and marks its card selected. */
    public void selectView(View next)
    {
        if (next != view)
        {
            clearSelection();
        }
        view = next;
        teamsShowing.accept(TeamTileOverlay.active(active, view));
        collisionsTile.setSelected(next == View.COLLISIONS);
        incompletesTile.setSelected(next == View.INCOMPLETES);
        teamsTile.setSelected(next == View.TEAMS);
        display.removeAll();
        JComponent shownView = next == View.COLLISIONS ? collisionsView.root()
            : next == View.INCOMPLETES ? incompletesView.root() : teamsView.root();
        display.add(shownView, BorderLayout.CENTER);
        display.revalidate();
        display.repaint();
    }

    /** EDT: where to say whether the Teams view is on screen; told at once. */
    public void setTeamsShowing(Consumer<Boolean> listener)
    {
        teamsShowing = listener == null ? showing -> { } : listener;
        teamsShowing.accept(TeamTileOverlay.active(active, view));
    }

    @Override
    public void onActivate()
    {
        active = true;
        teamsShowing.accept(TeamTileOverlay.active(active, view));
    }

    @Override
    public void onDeactivate()
    {
        active = false;
        teamsShowing.accept(false);
        clearSelection();
    }

    /** EDT: where to say which event tile to highlight; null clears it. */
    void setEventTile(Consumer<EventTileOverlay.EventTile> listener)
    {
        eventTile = listener == null ? tile -> { } : listener;
    }

    /** EDT, tests: the selected collision or incomplete row, or null. */
    Object selectedRow()
    {
        return selection.selected();
    }

    /** EDT: a click on a collision or incomplete row: select it (or unselect it) and highlight its tile. */
    void clickRow(Object row)
    {
        Object now = selection.click(row);
        eventTile.accept(tileOf(now));
        restyleLists();
    }

    private void clearSelection()
    {
        if (selection.selected() != null)
        {
            selection.clear();
            eventTile.accept(null);
            restyleLists();
        }
    }

    /** The overlay's tile for a row, in that list's accent colour; null for none. */
    static EventTileOverlay.EventTile tileOf(Object row)
    {
        if (row instanceof PanelModel.CollisionRow)
        {
            PanelModel.CollisionRow collision = (PanelModel.CollisionRow) row;
            return new EventTileOverlay.EventTile(collision.getSx(), collision.getSy(), collision.getStamp(),
                PanelStyle.COLLISION);
        }
        if (row instanceof PanelModel.IncompleteRow)
        {
            PanelModel.IncompleteRow incomplete = (PanelModel.IncompleteRow) row;
            return new EventTileOverlay.EventTile(incomplete.getSx(), incomplete.getSy(), incomplete.getStamp(),
                PanelStyle.INCOMPLETE);
        }
        return null;
    }

    private void restyleLists()
    {
        if (shown != null)
        {
            collisionsView.restyle();
            incompletesView.restyle();
        }
    }

    /** EDT: the selected view. */
    View view()
    {
        return view;
    }

    /** EDT, tests: the count card that selects {@code v}. */
    JComponent card(View target)
    {
        return target == View.COLLISIONS ? collisionsTile : target == View.INCOMPLETES ? incompletesTile : teamsTile;
    }

    /** EDT, tests: the replay card's preferred height. */
    int replayCardHeight()
    {
        return replayCard.preferredHeight();
    }

    /** EDT, tests: the width the record button needs for its current label. */
    int recordButtonWidth()
    {
        return recordButton.getPreferredSize().width;
    }

    /** EDT, tests: the record button itself, to hover, disable and read its colour. */
    JButton recordButton()
    {
        return recordButton;
    }

    // ---- Updates ----

    /**
     * EDT: the record button for the Record replays setting ({@code armed}) and whether a file is
     * open right now. Red text while armed, so stopping is one obvious click away; green while idle,
     * so starting reads as an action rather than a greyed-out button.
     */
    void setRecording(boolean armed, boolean recording)
    {
        recordArmed = armed;
        recordButton.setText(PanelModel.recordButtonText(armed));
        recordButton.setToolTipText(PanelModel.recordButtonTip(armed, recording));
        paintRecordButton();
    }

    /** EDT: the record button's text colour and cursor for its state ({@link PanelStyle#recordForeground}). */
    private void paintRecordButton()
    {
        boolean enabled = recordButton.isEnabled();
        recordButton.setForeground(PanelStyle.recordForeground(recordArmed, enabled, recordHover));
        recordButton.setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    /** EDT: renders a model, rebuilding only the parts that changed. */
    void update(PanelModel model)
    {
        PanelModel was = shown;
        shown = model;

        status.setText(model.status());
        status.setForeground(model.isInPoh() ? PanelStyle.OK : ColorScheme.LIGHT_GRAY_COLOR);
        status.setIcon(new SidebarWidgets.Dot(model.isInPoh() ? PanelStyle.OK : ColorScheme.MEDIUM_GRAY_COLOR));
        savedTick.setText(model.getSavedTick() == null ? "" : model.getSavedTick());
        savedTick.setVisible(model.getSavedTick() != null);
        replayCard.show(model.getReplay());

        collisionsTile.setCount(model.getCollisionCount());
        incompletesTile.setCount(model.getIncompleteCount());
        latestCard.show(model.getLatest());

        if (was == null || !model.getCollisions().equals(was.getCollisions()))
        {
            collisionsView.show(model.getCollisions());
        }
        if (was == null || !model.getIncompletes().equals(was.getIncompletes()))
        {
            incompletesView.show(model.getIncompletes());
        }
        // A selected row that was cleared or trimmed away takes its highlight with it.
        List<?> current = view == View.INCOMPLETES ? model.getIncompletes() : model.getCollisions();
        if (selection.retain(current))
        {
            eventTile.accept(null);
            restyleLists();
        }
        collisionsView.setCount(model.getCollisionCount());
        incompletesView.setCount(model.getIncompleteCount());

        PanelModel.TeamsState teams = model.getTeams();
        teamsTile.setSummary("<html><font color='" + SidebarWidgets.hex(Teams.TEAM_A_COLOR) + "'>A "
            + teams.getTeamA() + "</font>&nbsp;&nbsp;<font color='" + SidebarWidgets.hex(Teams.TEAM_B_COLOR) + "'>B "
            + teams.getTeamB() + "</font></html>");
        teamHint.setVisible(teams.isHint());
        if (was == null || !teams.equals(was.getTeams()))
        {
            teamsView.show(teams);
        }
        revalidate();
        repaint();
    }

    /** EDT: shows a Copy plugin history result for a few seconds. */
    void showCopyResult(String message)
    {
        copyResult.show(message);
    }

    /** Sidebar icon: the referee whistle and players, fitted into the toolbar's square. */
    static BufferedImage icon()
    {
        BufferedImage image = ImageUtil.loadImageResource(RflPanel.class, "rfl_icon.png");
        return ImageUtil.resizeCanvas(ImageUtil.resizeImage(image, ICON_SIZE, ICON_SIZE, true), ICON_SIZE, ICON_SIZE);
    }
}
