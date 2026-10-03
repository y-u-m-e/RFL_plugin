package com.rfl;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.text.DefaultCaret;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.ImageUtil;

/**
 * The "RFL" sidebar panel. Top to bottom: a status strip (house, recording) with a red banner when
 * a banned plugin is enabled; the session's collision and interception counts with the latest
 * event as a highlighted card; Collisions / Interceptions / Plugins / Debug tabs; and an Open
 * folder footer. It renders a {@link PanelModel} and formats nothing itself.
 *
 * <p>Threads: Swing EDT only. Models arrive through {@link #update}.
 */
final class RflPanel extends PluginPanel
{
    private static final Color COLLISION = ColorScheme.BRAND_ORANGE;
    private static final Color INTERCEPTION = new Color(0, 200, 255);
    private static final Color ALERT = new Color(200, 40, 40);
    private static final Color OK = ColorScheme.PROGRESS_COMPLETE_COLOR;
    private static final Color ON = new Color(90, 200, 90);
    private static final Color OFF = new Color(220, 90, 90);
    /** Width for wrapped HTML text in a card, in CSS pixels. */
    private static final int WRAP = 165;
    private static final int COPY_MESSAGE_MS = 3000;

    // Status strip and banner.
    private final JLabel status = new JLabel();
    private final JLabel recording = new JLabel("Recording");
    private final JButton recordButton = new JButton();
    private final JPanel banner = new JPanel(new BorderLayout());
    private final JLabel bannerText = new JLabel();

    // Hero.
    private final JLabel collisionCount = bigNumber(COLLISION);
    private final JLabel interceptionCount = bigNumber(INTERCEPTION);
    private final JPanel latestCard = new JPanel(new BorderLayout(0, 3));
    private final JLabel latestKind = new JLabel();
    private final JLabel latestTime = new JLabel();
    private final JLabel latestBody = new JLabel();

    // Tabs.
    private final JPanel collisionsList = vertical();
    private final JPanel interceptionsList = vertical();
    private final JPanel bannedList = vertical();
    private final JLabel enabledCount = bigNumberSmall();
    private final JLabel disabledCount = bigNumberSmall();
    private final JPanel togglesList = vertical();
    private final JLabel copyResult = new JLabel(" ");
    private final Timer copyResultTimer = new Timer(COPY_MESSAGE_MS, e -> setCopyText(" "));
    private final JTextArea debugText = new JTextArea();
    private final MaterialTabGroup tabs;
    private final MaterialTab collisionsTab;
    private final MaterialTab debugTab;

    private PanelModel shown;

    /**
     * @param openFolder runs on the EDT when Open folder is clicked
     * @param copyHistory runs on the EDT when Copy plugin history is clicked
     * @param toggleRecording runs on the EDT when the record button is clicked
     */
    RflPanel(Runnable openFolder, Runnable copyHistory, Runnable toggleRecording)
    {
        super(false);
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        top.add(statusStrip());
        top.add(Box.createVerticalStrut(6));
        top.add(banner());
        top.add(heroCounts());
        top.add(Box.createVerticalStrut(6));
        top.add(latestCard());
        top.add(Box.createVerticalStrut(8));

        ScrollingDisplay display = new ScrollingDisplay();
        tabs = new MaterialTabGroup(display);
        // MaterialTabGroup's FlowLayout sizes itself for one row, so at sidebar width the tabs that
        // wrap (Plugins, Debug) were clipped out of sight. Two per row, sized for every row.
        tabs.setLayout(new GridLayout(0, 2, 4, 4));
        tabs.setAlignmentX(LEFT_ALIGNMENT);
        collisionsTab = new MaterialTab("Collisions", tabs, collisionsTab());
        MaterialTab interceptionsTab = new MaterialTab("Interceptions", tabs, interceptionsTab());
        MaterialTab pluginsTab = new MaterialTab("Plugins", tabs, pluginsTab(copyHistory));
        debugTab = new MaterialTab("Debug", tabs, debugTab());
        debugTab.setVisible(false);
        tabs.addTab(collisionsTab);
        tabs.addTab(interceptionsTab);
        tabs.addTab(pluginsTab);
        tabs.addTab(debugTab);
        top.add(tabs);
        add(top, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(display, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        recordButton.setFocusable(false);
        recordButton.addActionListener(e -> toggleRecording.run());
        setRecording(false, false);
        JButton open = new JButton("Open folder");
        open.setToolTipText("Opens the RFL folder: collisions, plugin history and replays.");
        open.setFocusable(false);
        open.addActionListener(e -> openFolder.run());
        JPanel footer = new JPanel(new GridLayout(1, 2, 4, 0));
        footer.setOpaque(false);
        footer.add(recordButton);
        footer.add(open);
        add(footer, BorderLayout.SOUTH);

        copyResultTimer.setRepeats(false);
        tabs.select(collisionsTab);
        update(PanelModel.of(false, false, List.of(), 0, 0, List.of(), List.of(), null, List.of(), null,
            java.time.ZoneId.systemDefault()));
    }

    // ---- Layout ----

    private JComponent statusStrip()
    {
        JPanel strip = new JPanel(new BorderLayout());
        strip.setOpaque(false);
        strip.setAlignmentX(LEFT_ALIGNMENT);
        status.setFont(FontManager.getRunescapeBoldFont());
        recording.setFont(FontManager.getRunescapeBoldFont());
        recording.setForeground(ALERT.brighter());
        recording.setIcon(new Dot(ALERT.brighter()));
        recording.setToolTipText("A replay file is open.");
        strip.add(status, BorderLayout.WEST);
        strip.add(recording, BorderLayout.EAST);
        return capHeight(strip);
    }

    private JComponent banner()
    {
        banner.setBackground(ALERT);
        banner.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        banner.setAlignmentX(LEFT_ALIGNMENT);
        bannerText.setForeground(Color.WHITE);
        bannerText.setFont(FontManager.getRunescapeBoldFont());
        banner.add(bannerText, BorderLayout.CENTER);
        banner.setVisible(false);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setAlignmentX(LEFT_ALIGNMENT);
        wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        wrap.add(banner, BorderLayout.CENTER);
        return capHeight(wrap);
    }

    private JComponent heroCounts()
    {
        JPanel row = new JPanel(new GridLayout(1, 2, 6, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(tile(collisionCount, "COLLISIONS", COLLISION));
        row.add(tile(interceptionCount, "INTERCEPTIONS", INTERCEPTION));
        return capHeight(row);
    }

    private static JComponent tile(JLabel number, String caption, Color accent)
    {
        JPanel tile = new JPanel(new BorderLayout());
        tile.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        tile.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(3, 0, 0, 0, accent),
            BorderFactory.createEmptyBorder(4, 6, 6, 6)));
        JLabel label = new JLabel(caption);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        tile.add(number, BorderLayout.CENTER);
        tile.add(label, BorderLayout.SOUTH);
        return tile;
    }

    private JComponent latestCard()
    {
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        latestKind.setFont(FontManager.getRunescapeBoldFont());
        latestTime.setFont(FontManager.getRunescapeSmallFont());
        latestTime.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        head.add(latestKind, BorderLayout.WEST);
        head.add(latestTime, BorderLayout.EAST);
        latestBody.setFont(FontManager.getRunescapeFont());
        latestBody.setForeground(Color.WHITE);
        latestCard.add(head, BorderLayout.NORTH);
        latestCard.add(latestBody, BorderLayout.CENTER);
        latestCard.setAlignmentX(LEFT_ALIGNMENT);
        return capHeight(latestCard);
    }

    private JComponent collisionsTab()
    {
        JPanel tab = vertical();
        tab.add(hint("Newest first. Bold: had the ball."));
        tab.add(collisionsList);
        return tab;
    }

    private JComponent interceptionsTab()
    {
        JPanel tab = vertical();
        tab.add(hint("Newest first."));
        tab.add(interceptionsList);
        return tab;
    }

    private JComponent pluginsTab(Runnable copyHistory)
    {
        JPanel tab = vertical();
        tab.add(bannedList);
        tab.add(Box.createVerticalStrut(6));

        JPanel counts = new JPanel(new GridLayout(1, 2, 6, 0));
        counts.setOpaque(false);
        counts.setAlignmentX(LEFT_ALIGNMENT);
        counts.add(tile(enabledCount, "ENABLED", ON));
        counts.add(tile(disabledCount, "DISABLED", ColorScheme.MEDIUM_GRAY_COLOR));
        tab.add(capHeight(counts));
        tab.add(Box.createVerticalStrut(8));

        JButton copy = new JButton("Copy plugin history");
        copy.setToolTipText("Copies today's plugin log file (rfl/plugins) to the clipboard, as is.");
        copy.setFocusable(false);
        copy.addActionListener(e -> copyHistory.run());
        JPanel copyRow = new JPanel(new BorderLayout(0, 2));
        copyRow.setOpaque(false);
        copyRow.setAlignmentX(LEFT_ALIGNMENT);
        copyResult.setFont(FontManager.getRunescapeSmallFont());
        copyResult.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        copyRow.add(copy, BorderLayout.NORTH);
        copyRow.add(copyResult, BorderLayout.SOUTH);
        tab.add(capHeight(copyRow));
        tab.add(Box.createVerticalStrut(6));

        tab.add(header("TODAY'S TOGGLES"));
        tab.add(togglesList);
        return tab;
    }

    private JComponent debugTab()
    {
        debugText.setEditable(false);
        debugText.setLineWrap(true);
        debugText.setWrapStyleWord(true);
        debugText.setFont(FontManager.getRunescapeSmallFont());
        debugText.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        debugText.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        debugText.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        // Keep the reader's scroll position when the text refreshes.
        ((DefaultCaret) debugText.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        return debugText;
    }

    // ---- Updates ----

    /**
     * EDT: the record button for the Record replays setting ({@code armed}) and whether a file is
     * open right now. Red text while armed, so stopping is one obvious click away.
     */
    void setRecording(boolean armed, boolean recording)
    {
        recordButton.setText(PanelModel.recordButtonText(armed));
        recordButton.setToolTipText(PanelModel.recordButtonTip(armed, recording));
        recordButton.setForeground(armed ? ALERT.brighter() : ColorScheme.LIGHT_GRAY_COLOR);
    }

    /** EDT: renders a model, rebuilding only the parts that changed. */
    void update(PanelModel m)
    {
        PanelModel was = shown;
        shown = m;

        status.setText(m.status());
        status.setForeground(m.isInPoh() ? OK : ColorScheme.LIGHT_GRAY_COLOR);
        status.setIcon(m.isInPoh() ? new Dot(OK) : new Dot(ColorScheme.MEDIUM_GRAY_COLOR));
        recording.setVisible(m.isRecording());

        String alert = m.bannedAlert();
        banner.setVisible(alert != null);
        if (alert != null)
        {
            bannerText.setText(wrap(PanelModel.html(alert)));
        }

        collisionCount.setText(String.valueOf(m.getCollisionCount()));
        interceptionCount.setText(String.valueOf(m.getInterceptionCount()));
        showLatest(m.getLatest());

        if (was == null || !m.getCollisions().equals(was.getCollisions()))
        {
            showCollisions(m.getCollisions());
        }
        if (was == null || !m.getInterceptions().equals(was.getInterceptions()))
        {
            showInterceptions(m.getInterceptions());
        }
        if (was == null || !m.getBannedOn().equals(was.getBannedOn()) || m.isPluginsKnown() != was.isPluginsKnown())
        {
            showBanned(m);
        }
        enabledCount.setText(m.isPluginsKnown() ? String.valueOf(m.getEnabledCount()) : "-");
        disabledCount.setText(m.isPluginsKnown() ? String.valueOf(m.getDisabledCount()) : "-");
        if (was == null || !m.getToggles().equals(was.getToggles()))
        {
            showToggles(m.getToggles());
        }

        boolean debug = m.getDebugText() != null;
        if (debug && !Objects.equals(m.getDebugText(), debugText.getText()))
        {
            debugText.setText(m.getDebugText());
        }
        if (debugTab.isVisible() != debug)
        {
            debugTab.setVisible(debug);
            if (!debug && debugTab.isSelected())
            {
                tabs.select(collisionsTab);
            }
            tabs.revalidate();
        }
        revalidate();
        repaint();
    }

    /** EDT: shows a Copy plugin history result for a few seconds. */
    void showCopyResult(String message)
    {
        setCopyText(message);
        copyResultTimer.restart();
    }

    private void setCopyText(String message)
    {
        copyResult.setText(message);
    }

    private void showLatest(PanelModel.LatestEvent e)
    {
        if (e == null)
        {
            latestCard.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            latestCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
            latestKind.setText("LATEST");
            latestKind.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            latestTime.setText("");
            latestBody.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            latestBody.setText(wrap("No collisions or interceptions yet this session."));
            return;
        }
        Color accent = e.getKind() == PanelModel.Kind.INTERCEPTION ? INTERCEPTION : COLLISION;
        latestCard.setBackground(blend(ColorScheme.DARKER_GRAY_COLOR, accent, 0.18f));
        latestCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 4, 0, 0, accent),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        latestKind.setText(e.getKind().label.toUpperCase());
        latestKind.setForeground(accent);
        latestTime.setText(e.getTime());
        latestBody.setForeground(Color.WHITE);
        latestBody.setText(wrap("<b>" + arrows(PanelModel.html(e.getBody())) + "</b>"));
    }

    private void showCollisions(List<PanelModel.CollisionRow> rows)
    {
        collisionsList.removeAll();
        if (rows.isEmpty())
        {
            collisionsList.add(empty("No collisions this session."));
        }
        for (PanelModel.CollisionRow r : rows)
        {
            JPanel row = row();
            row.add(line(small(r.getTime()), small("overlap " + r.getOverlap())), BorderLayout.NORTH);
            JLabel pair = new JLabel("<html>" + arrows(PanelModel.pairHtml(r)) + "</html>");
            pair.setFont(FontManager.getRunescapeFont());
            pair.setForeground(Color.WHITE);
            pair.setToolTipText(PanelModel.ball(r));
            row.add(pair, BorderLayout.CENTER);
            collisionsList.add(row);
        }
    }

    private void showInterceptions(List<PanelModel.InterceptionRow> rows)
    {
        interceptionsList.removeAll();
        if (rows.isEmpty())
        {
            interceptionsList.add(empty("No interceptions this session."));
        }
        for (PanelModel.InterceptionRow r : rows)
        {
            JPanel row = row();
            row.add(small(r.getTime()), BorderLayout.NORTH);
            JLabel receiver = new JLabel(r.getReceiver());
            receiver.setFont(FontManager.getRunescapeBoldFont());
            receiver.setForeground(INTERCEPTION);
            row.add(receiver, BorderLayout.CENTER);
            JLabel contacts = new JLabel(wrap(PanelModel.html(r.getContacts())));
            contacts.setFont(FontManager.getRunescapeSmallFont());
            contacts.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            row.add(contacts, BorderLayout.SOUTH);
            interceptionsList.add(row);
        }
    }

    private void showBanned(PanelModel m)
    {
        bannedList.removeAll();
        if (!m.isPluginsKnown())
        {
            bannedList.add(empty("Plugin list not read yet."));
            return;
        }
        if (m.getBannedOn().isEmpty())
        {
            JLabel ok = new JLabel("No banned plugins enabled");
            ok.setFont(FontManager.getRunescapeBoldFont());
            ok.setForeground(OK);
            ok.setIcon(new Dot(OK));
            ok.setAlignmentX(LEFT_ALIGNMENT);
            bannedList.add(ok);
            return;
        }
        bannedList.add(header("BANNED, ENABLED NOW"));
        for (String name : m.getBannedOn())
        {
            JLabel bad = new JLabel(name);
            bad.setFont(FontManager.getRunescapeBoldFont());
            bad.setForeground(OFF);
            bad.setIcon(new Dot(ALERT.brighter()));
            bad.setAlignmentX(LEFT_ALIGNMENT);
            bannedList.add(bad);
        }
    }

    private void showToggles(List<PanelModel.ToggleRow> rows)
    {
        togglesList.removeAll();
        if (rows.isEmpty())
        {
            togglesList.add(empty("No plugins turned on or off today."));
        }
        for (PanelModel.ToggleRow r : rows)
        {
            JPanel row = row();
            row.setLayout(new BorderLayout(6, 0));
            row.add(small(r.getTime()), BorderLayout.WEST);
            JLabel name = new JLabel(r.getPlugin());
            name.setFont(FontManager.getRunescapeSmallFont());
            name.setForeground(PluginSnapshotter.isBanned(r.getPlugin()) ? OFF : Color.WHITE);
            name.setToolTipText(r.getPlugin());
            row.add(name, BorderLayout.CENTER);
            JLabel state = new JLabel(r.isOn() ? "ON" : "OFF");
            state.setFont(FontManager.getRunescapeBoldFont());
            state.setForeground(r.isOn() ? ON : OFF);
            row.add(state, BorderLayout.EAST);
            togglesList.add(row);
        }
    }

    // ---- Small builders ----

    private static JLabel bigNumber(Color color)
    {
        JLabel label = new JLabel("0");
        label.setFont(FontManager.getRunescapeBoldFont().deriveFont(32f));
        label.setForeground(color);
        return label;
    }

    private static JLabel bigNumberSmall()
    {
        JLabel label = new JLabel("-");
        label.setFont(FontManager.getRunescapeBoldFont().deriveFont(24f));
        label.setForeground(Color.WHITE);
        return label;
    }

    /** A vertical list that is as tall as its rows and as wide as its parent. */
    private static JPanel vertical()
    {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        list.setAlignmentX(LEFT_ALIGNMENT);
        return list;
    }

    private static JPanel row()
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
            BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        row.setAlignmentX(LEFT_ALIGNMENT);
        return row;
    }

    private static JComponent line(JLabel left, JLabel right)
    {
        JPanel line = new JPanel(new BorderLayout());
        line.setOpaque(false);
        line.add(left, BorderLayout.WEST);
        line.add(right, BorderLayout.EAST);
        return line;
    }

    private static JLabel small(String text)
    {
        JLabel label = new JLabel(text);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        return label;
    }

    private static JLabel hint(String text)
    {
        JLabel label = small(text);
        label.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel header(String text)
    {
        JLabel label = new JLabel(text);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        label.setBorder(BorderFactory.createEmptyBorder(2, 0, 3, 0));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel empty(String text)
    {
        JLabel label = small(text);
        label.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        label.setAlignmentX(LEFT_ALIGNMENT);
        label.setHorizontalAlignment(SwingConstants.LEFT);
        return label;
    }

    /** Caps a component's maximum height at its preferred height, so a vertical box doesn't stretch it. */
    private static JComponent capHeight(JComponent c)
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
        holder.setAlignmentX(LEFT_ALIGNMENT);
        holder.add(c, BorderLayout.CENTER);
        return holder;
    }

    /** Wrapped HTML at the card width; the argument is already escaped. */
    private static String wrap(String escapedHtml)
    {
        return "<html><body style='width:" + WRAP + "px'>" + escapedHtml + "</body></html>";
    }

    /** The RuneScape fonts have no "↔" glyph, so it is drawn in the logical Dialog font. */
    private static String arrows(String html)
    {
        return html.replace("↔", "<font face='Dialog'>&harr;</font>");
    }

    private static Color blend(Color base, Color tint, float amount)
    {
        return new Color(
            Math.round(base.getRed() + (tint.getRed() - base.getRed()) * amount),
            Math.round(base.getGreen() + (tint.getGreen() - base.getGreen()) * amount),
            Math.round(base.getBlue() + (tint.getBlue() - base.getBlue()) * amount));
    }

    /** A small filled circle, for the status strip and plugin lines. */
    private static final class Dot implements Icon
    {
        private final Color color;

        Dot(Color color)
        {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x + 1, y + 2, 7, 7);
            g2.dispose();
        }

        @Override
        public int getIconWidth()
        {
            return 10;
        }

        @Override
        public int getIconHeight()
        {
            return 11;
        }
    }

    /** The tab display: as wide as the scroll viewport, as tall as the selected tab's content. */
    private static final class ScrollingDisplay extends JPanel implements Scrollable
    {
        ScrollingDisplay()
        {
            super(new BorderLayout());
            setBackground(ColorScheme.DARK_GRAY_COLOR);
            setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize()
        {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
        {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
        {
            return Math.max(16, visibleRect.height - 16);
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

    /** Sidebar icon: the referee whistle and players, fitted into the toolbar's 16 px square. */
    static BufferedImage icon()
    {
        BufferedImage image = ImageUtil.loadImageResource(RflPanel.class, "rfl_icon.png");
        return ImageUtil.resizeCanvas(ImageUtil.resizeImage(image, 16, 16, true), 16, 16);
    }
}
