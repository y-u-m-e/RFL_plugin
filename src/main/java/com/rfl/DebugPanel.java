package com.rfl;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.text.DefaultCaret;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;

/**
 * "RFL Debug" sidebar panel: an Open folder button for the saved collision files, then a read-only
 * text view of what contact detection sees and the latest saved collisions. Swing EDT only; the
 * text is built on the client thread and handed over with {@link #show}.
 */
final class DebugPanel extends PluginPanel
{
    private final JTextArea text = new JTextArea();

    /** @param openFolder runs on the EDT when Open folder is clicked */
    DebugPanel(Runnable openFolder)
    {
        super(false);
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel title = new JLabel("RFL Debug");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(Color.WHITE);
        JButton open = new JButton("Open folder");
        open.setToolTipText("Opens the folder the daily collision files are saved in.");
        open.setFocusable(false);
        open.addActionListener(e -> openFolder.run());

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        title.setAlignmentX(LEFT_ALIGNMENT);
        open.setAlignmentX(LEFT_ALIGNMENT);
        top.add(title);
        top.add(open);
        add(top, BorderLayout.NORTH);

        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(FontManager.getRunescapeSmallFont());
        text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        text.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        text.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        text.setText("Waiting for the client...");
        // Keep the reader's scroll position when the text refreshes.
        ((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        JScrollPane scroll = new JScrollPane(text, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);
    }

    /** EDT only. */
    void show(String snapshot)
    {
        if (!snapshot.equals(text.getText()))
        {
            text.setText(snapshot);
        }
    }

    /** Sidebar icon: the RFL football, scaled to the toolbar's 16 px. */
    static BufferedImage icon()
    {
        return ImageUtil.resizeImage(ImageUtil.loadImageResource(DebugPanel.class, "debug_icon.png"), 16, 16);
    }
}
