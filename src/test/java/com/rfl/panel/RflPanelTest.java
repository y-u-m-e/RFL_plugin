package com.rfl.panel;

import com.rfl.replay.ReplayState;
import com.rfl.replay.ReplayStatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.MouseEvent;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import org.junit.Test;

/**
 * {@link RflPanel} layout that must not move: the replay card keeps one height in every state, and
 * the footer buttons fit the sidebar, and the record button has a distinct colour per state. Built off screen on the EDT.
 */
public class RflPanelTest
{
    private static final String FILE = "2026-10-03_114112_w354.rflr.gz";
    private static final long T = 1_790_019_730_000L;

    private static RflPanel panel() throws Exception
    {
        AtomicReference<RflPanel> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(new RflPanel(() -> { }, () -> { }, () -> { }, () -> { },
            () -> { }, (name, team) -> { }, () -> { })));
        return out.get();
    }

    private static PanelModel model(PanelModel.ReplayStrip strip)
    {
        return PanelModel.of(true, false, 0, 0, List.of(), List.of(), null, strip, null, ZoneOffset.UTC);
    }

    @Test
    public void replayCardIsOneHeightInEveryState() throws Exception
    {
        RflPanel panel = panel();
        List<PanelModel.ReplayStrip> strips = new ArrayList<>();
        strips.add(PanelModel.replayStrip(ReplayStatus.IDLE, false, T));
        strips.add(PanelModel.replayStrip(ReplayStatus.IDLE, true, T));
        strips.add(PanelModel.replayStrip(new ReplayStatus(ReplayState.RECORDING, FILE, 3_751_000L,
            1_468_006L, 12_345, 0.0, null, 0L), true, T));
        strips.add(PanelModel.replayStrip(new ReplayStatus(ReplayState.SAVING, FILE, 0L, 1_468_006L, 132,
            0.42, null, 0L), true, T));
        strips.add(PanelModel.replayStrip(new ReplayStatus(ReplayState.SAVED, FILE + FILE + FILE, 0L,
            1_468_006L, 132, 1.0, null, T), true, T));
        strips.add(PanelModel.replayStrip(new ReplayStatus(ReplayState.ERROR, FILE, 0L, 0L, 0, 0.0,
            "a very long reason that can never fit on one line of the sidebar card at all", 0L), true, T));
        List<Integer> heights = new ArrayList<>();
        for (PanelModel.ReplayStrip strip : strips)
        {
            SwingUtilities.invokeAndWait(() ->
            {
                panel.update(model(strip));
                heights.add(panel.replayCardHeight());
            });
        }
        for (int i = 1; i < heights.size(); i++)
        {
            assertEquals("state " + strips.get(i).getState() + " vs idle: " + heights, heights.get(0), heights.get(i));
        }
    }

    @Test
    public void recordButtonFitsTheSidebarWithItsLongestLabel() throws Exception
    {
        RflPanel panel = panel();
        int usable = PluginPanel.PANEL_WIDTH - 2 * RflPanel.SIDE;
        for (boolean armed : new boolean[] { false, true })
        {
            AtomicReference<Integer> width = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() ->
            {
                panel.setRecording(armed, false);
                width.set(panel.recordButtonWidth());
            });
            assertTrue(PanelModel.recordButtonText(armed) + " needs " + width.get() + " of " + usable,
                width.get() <= usable);
        }
    }

    @Test
    public void recordButtonIsGreenIdleRedArmedAndGreyDisabled() throws Exception
    {
        RflPanel panel = panel();
        SwingUtilities.invokeAndWait(() ->
        {
            JButton button = panel.recordButton();

            panel.setRecording(false, false);
            assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, button.getForeground());
            assertEquals(Cursor.HAND_CURSOR, button.getCursor().getType());

            button.dispatchEvent(new MouseEvent(button, MouseEvent.MOUSE_ENTERED, 0L, 0, 5, 5, 0, false));
            Color hover = button.getForeground();
            assertNotEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, hover);
            assertTrue("hover is lighter", hover.getRed() + hover.getGreen() + hover.getBlue()
                > PanelStyle.RECORD_IDLE.getRed() + PanelStyle.RECORD_IDLE.getGreen() + PanelStyle.RECORD_IDLE.getBlue());
            button.dispatchEvent(new MouseEvent(button, MouseEvent.MOUSE_EXITED, 0L, 0, 5, 5, 0, false));
            assertEquals(PanelStyle.RECORD_IDLE, button.getForeground());

            panel.setRecording(true, true);
            assertEquals(PanelStyle.ALERT.brighter(), button.getForeground());
            panel.setRecording(true, false);
            assertEquals(PanelStyle.ALERT.brighter(), button.getForeground());

            panel.setRecording(false, false);
            button.setEnabled(false);
            assertEquals(PanelStyle.RECORD_DISABLED, button.getForeground());
            assertNotEquals(PanelStyle.RECORD_IDLE, button.getForeground());
            assertEquals(Cursor.DEFAULT_CURSOR, button.getCursor().getType());

            button.setEnabled(true);
            assertEquals(PanelStyle.RECORD_IDLE, button.getForeground());
        });
    }

    private static void press(JComponent c)
    {
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 5, 5, 1, false,
            MouseEvent.BUTTON1));
    }

    @Test
    public void countCardsSwitchTheView() throws Exception
    {
        RflPanel panel = panel();
        SwingUtilities.invokeAndWait(() ->
        {
            assertEquals(RflPanel.View.COLLISIONS, panel.view());
            press(panel.card(RflPanel.View.INCOMPLETES));
            assertEquals(RflPanel.View.INCOMPLETES, panel.view());
            press(panel.card(RflPanel.View.TEAMS));
            assertEquals(RflPanel.View.TEAMS, panel.view());
            press(panel.card(RflPanel.View.COLLISIONS));
            assertEquals(RflPanel.View.COLLISIONS, panel.view());
        });
    }
}
