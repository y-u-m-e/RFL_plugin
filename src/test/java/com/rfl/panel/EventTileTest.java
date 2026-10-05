package com.rfl.panel;

import com.rfl.Fixtures;
import com.rfl.contact.Collision;
import com.rfl.overlay.EventTileOverlay;
import sh.yumekui.toolkit.scene.SceneStamps;
import sh.yumekui.toolkit.swing.ToggleSelection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import org.junit.Test;

/** Clicking a collision or incomplete row highlights its recorded tile, only in the scene it happened in. */
public class EventTileTest
{
    /** The scene tile the fixture collision recorded. */
    private static final int SCENE_X = 52;
    private static final int SCENE_Y = 61;

    private static Collision collision(String a, long t)
    {
        return Fixtures.collision(a, "Bob").at(t, 1000).tile(3000, 3200, 0, SCENE_X, SCENE_Y).build();
    }

    @Test
    public void selectionClickTogglesMovesAndClears()
    {
        ToggleSelection s = new ToggleSelection();
        assertEquals("a", s.click("a"));
        assertTrue(s.isSelected("a"));
        assertEquals("another row moves it", "b", s.click("b"));
        assertFalse(s.isSelected("a"));
        assertNull("the same row again clears it", s.click("b"));
        s.click("c");
        assertFalse(s.retain(List.of("c", "d")));
        assertTrue("gone from the list", s.retain(List.of("d")));
        assertNull(s.selected());
        s.click("d");
        s.clear();
        assertNull(s.selected());
    }

    @Test
    public void staleSceneOrPlaneIsNeverDrawn()
    {
        SceneStamps stamps = new SceneStamps();
        int then = stamps.stamp(0);
        EventTileOverlay.EventTile tile = new EventTileOverlay.EventTile(52, 61, then, Color.ORANGE);
        assertTrue(EventTileOverlay.drawable(tile, stamps.stamp(0)));
        assertFalse("another plane", EventTileOverlay.drawable(tile, stamps.stamp(1)));
        stamps.bump();
        assertFalse("scene rebuilt since", EventTileOverlay.drawable(tile, stamps.stamp(0)));
        assertFalse("no stamp", EventTileOverlay.drawable(
            new EventTileOverlay.EventTile(52, 61, SceneStamps.NONE, Color.ORANGE), SceneStamps.NONE));
        assertFalse("no recorded tile", EventTileOverlay.drawable(
            new EventTileOverlay.EventTile(-1, -1, then, Color.ORANGE), then));
        assertFalse(EventTileOverlay.drawable(null, then));
    }

    @Test
    public void sessionEventsKeepEachEventsStampUntilCleared()
    {
        SessionEvents s = new SessionEvents();
        Collision c = collision("Amy", 1);
        s.onEvent(c, 12);
        assertEquals(12, s.stamp(c));
        PanelModel m = PanelModel.of(true, false, 1, 0, s.collisions(), List.of(), c, null, null,
            PanelModel.TeamsState.NONE, s::stamp, ZoneOffset.UTC);
        PanelModel.CollisionRow row = m.getCollisions().get(0);
        assertEquals(52, row.getSx());
        assertEquals(61, row.getSy());
        assertEquals(12, row.getStamp());
        s.clearCollisions();
        assertEquals(SceneStamps.NONE, s.stamp(c));
    }

    @Test
    public void rowClickHighlightsInTheCollisionColourAndLeavingClearsIt() throws Exception
    {
        List<EventTileOverlay.EventTile> heard = new ArrayList<>();
        AtomicReference<Object> afterLeave = new AtomicReference<>("unset");
        SwingUtilities.invokeAndWait(() ->
        {
            RflPanel panel = new RflPanel(() -> { }, () -> { }, () -> { }, () -> { }, () -> { }, (n, t) -> { },
                () -> { });
            panel.setEventTile(heard::add);
            SessionEvents s = new SessionEvents();
            Collision c = collision("Amy", 1);
            s.onEvent(c, 8);
            PanelModel m = PanelModel.of(true, false, 1, 0, s.collisions(), List.of(), c,
                PanelModel.replayStrip(null, false, 0L), null, PanelModel.TeamsState.NONE, s::stamp, ZoneOffset.UTC);
            panel.update(m);
            PanelModel.CollisionRow row = m.getCollisions().get(0);

            panel.clickRow(row);
            assertSame(row, panel.selectedRow());
            panel.selectView(RflPanel.View.INCOMPLETES);
            afterLeave.set(panel.selectedRow());

            panel.selectView(RflPanel.View.COLLISIONS);
            panel.clickRow(row);
            panel.onDeactivate();
        });
        assertNull("leaving the view clears it", afterLeave.get());
        assertEquals(4, heard.size());
        assertEquals(new EventTileOverlay.EventTile(52, 61, 8, heard.get(0).getColor()), heard.get(0));
        assertEquals(RflPanel.tileOf(new PanelModel.CollisionRow("", "", "", false, false, 0, 0, 0, 0)).getColor(),
            heard.get(0).getColor());
        assertNull(heard.get(1));
        assertEquals(heard.get(0), heard.get(2));
        assertNull("hiding the panel clears it", heard.get(3));
    }
}
