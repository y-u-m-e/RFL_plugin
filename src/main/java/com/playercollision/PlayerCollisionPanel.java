package com.playercollision;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel for exporting and checking plugin lists for RFL referee workflow.
 */
@Singleton
public class PlayerCollisionPanel extends PluginPanel
{
    private final JLabel summaryLabel = new JLabel("No checks run yet.");
    private final JTextArea resultArea = new JTextArea();
    private final JScrollPane resultScrollPane;
    private final JTextField matchIdField = new JTextField();

    private PlayerCollisionPlugin plugin;

    /**
     * Creates the plugin side panel and initializes its UI layout.
     */
    @Inject
    public PlayerCollisionPanel()
    {
        super(false);
        setLayout(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(10, 10, 10, 10));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        final JPanel header = buildHeader();
        add(header, BorderLayout.NORTH);

        resultArea.setEditable(false);
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        resultArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        resultArea.setForeground(Color.WHITE);
        resultArea.setFont(FontManager.getRunescapeSmallFont());
        resultArea.setBorder(new EmptyBorder(8, 8, 8, 8));
        resultArea.setText("Use \"Copy My Plugin List\" to export your active plugins.\n"
            + "Use \"Check Clipboard List\" to analyze a pasted player list.");

        resultScrollPane = new JScrollPane(resultArea);
        resultScrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        resultScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(resultScrollPane, BorderLayout.CENTER);

        add(buildFooter(), BorderLayout.SOUTH);
    }

    /**
     * Attaches the plugin instance so UI actions can call plugin operations.
     *
     * @param plugin plugin backing this panel
     */
    public void setPlugin(final PlayerCollisionPlugin plugin)
    {
        this.plugin = plugin;
    }

    /**
     * Updates the summary and report text shown in the panel.
     *
     * @param summary short status summary
     * @param details detailed multi-line results
     */
    public void setResultText(final String summary, final String details)
    {
        summaryLabel.setText(summary == null || summary.trim().isEmpty() ? "No checks run yet." : summary);
        resultArea.setText(details == null ? "" : details);
        resultArea.setCaretPosition(0);
        resultArea.revalidate();
        resultArea.repaint();
    }

    /**
     * Builds the top header section for the panel.
     *
     * @return header container
     */
    private JPanel buildHeader()
    {
        final JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);

        final JLabel title = new JLabel("RFL Plugin Ref Check");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(ColorScheme.BRAND_ORANGE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        final JLabel subtitle = new JLabel("Copy and verify plugin lists");
        subtitle.setFont(FontManager.getRunescapeSmallFont());
        subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        summaryLabel.setFont(FontManager.getRunescapeSmallFont());
        summaryLabel.setForeground(Color.WHITE);
        summaryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        header.add(title);
        header.add(Box.createVerticalStrut(2));
        header.add(subtitle);
        header.add(Box.createVerticalStrut(6));
        header.add(summaryLabel);
        return header;
    }

    /**
     * Builds the bottom action area containing the clear button.
     *
     * @return footer container
     */
    private JPanel buildFooter()
    {
        final JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setOpaque(false);

        final JLabel matchIdLabel = new JLabel("Match ID");
        matchIdLabel.setFont(FontManager.getRunescapeSmallFont());
        matchIdLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        matchIdLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        matchIdField.setFont(FontManager.getRunescapeSmallFont());
        matchIdField.setAlignmentX(Component.LEFT_ALIGNMENT);
        matchIdField.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 24));
        matchIdField.setText("match-" + System.currentTimeMillis());

        final JButton copyButton = buildActionButton("Copy My Plugin List", new Color(45, 90, 140));
        copyButton.addActionListener((event) ->
        {
            if (plugin != null)
            {
                plugin.copyMyPluginListToClipboard();
            }
        });

        final JButton checkButton = buildActionButton("Check Clipboard List", new Color(45, 120, 65));
        checkButton.addActionListener((event) ->
        {
            if (plugin != null)
            {
                plugin.checkClipboardList();
            }
        });

        final JButton clearButton = buildActionButton("Clear Results", new Color(90, 45, 45));
        clearButton.addActionListener((event) ->
        {
            if (plugin != null)
            {
                plugin.clearResultText();
            }
        });

        final JButton startSessionButton = buildActionButton("Start Match Session", new Color(70, 120, 45));
        startSessionButton.addActionListener((event) ->
        {
            if (plugin != null)
            {
                plugin.startMatchSession(matchIdField.getText());
            }
        });

        final JButton stopSessionButton = buildActionButton("Stop Match Session", new Color(130, 90, 35));
        stopSessionButton.addActionListener((event) ->
        {
            if (plugin != null)
            {
                plugin.stopMatchSession();
            }
        });

        final JButton verifySessionButton = buildActionButton("Verify Latest Session Log", new Color(55, 100, 130));
        verifySessionButton.addActionListener((event) ->
        {
            if (plugin != null)
            {
                plugin.verifyLatestSessionLog();
            }
        });

        footer.add(matchIdLabel);
        footer.add(Box.createVerticalStrut(2));
        footer.add(matchIdField);
        footer.add(Box.createVerticalStrut(6));
        footer.add(startSessionButton);
        footer.add(Box.createVerticalStrut(4));
        footer.add(stopSessionButton);
        footer.add(Box.createVerticalStrut(4));
        footer.add(verifySessionButton);
        footer.add(Box.createVerticalStrut(8));
        footer.add(copyButton);
        footer.add(Box.createVerticalStrut(4));
        footer.add(checkButton);
        footer.add(Box.createVerticalStrut(4));
        footer.add(clearButton);
        return footer;
    }

    /**
     * Builds a styled footer action button.
     *
     * @param text button label text
     * @param color button background color
     * @return configured action button
     */
    private JButton buildActionButton(final String text, final Color color)
    {
        final JButton button = new JButton(text);
        button.setFont(FontManager.getRunescapeSmallFont());
        button.setFocusable(false);
        button.setForeground(Color.WHITE);
        button.setBackground(color);
        button.setBorder(new EmptyBorder(6, 8, 6, 8));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        return button;
    }
}
