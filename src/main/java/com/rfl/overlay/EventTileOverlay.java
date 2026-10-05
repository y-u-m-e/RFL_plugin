package com.rfl.overlay;

import sh.yumekui.toolkit.overlay.TilePainter;
import sh.yumekui.toolkit.scene.SceneStamps;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The tile of the collision or incomplete selected in the panel, outlined and filled (alpha
 * {@link TeamTileOverlay#FILL_ALPHA}) in that list's accent colour. It uses the scene tile the
 * event recorded and draws it only while the same scene and plane are loaded
 * ({@link SceneStamps}); after a reload the selection is simply not drawn, never drawn elsewhere.
 * Nothing selected: render returns at once.
 *
 * <p>Threads: {@link #select} from the EDT, {@link #render} on the client thread.
 */
@Singleton
public final class EventTileOverlay extends Overlay
{
    private static final BasicStroke STROKE = new BasicStroke(2);

    /** A selected event's recorded scene tile, its scene stamp, and the colour to draw it in. */
    @Value
    public static class EventTile
    {
        int sx;
        int sy;
        int stamp;
        Color color;
    }

    private final Client client;
    private final SceneStamps stamps;
    private volatile EventTile selected;

    @Inject
    EventTileOverlay(Client client, SceneStamps stamps)
    {
        this.client = client;
        this.stamps = stamps;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    /** EDT: the selected event's tile, or null for none. */
    public void select(EventTile tile)
    {
        selected = tile;
    }

    /** Whether {@code tile} may be drawn in the scene stamped {@code current}: same scene and plane, a real tile. */
    public static boolean drawable(EventTile tile, int current)
    {
        return tile != null && tile.sx >= 0 && tile.sy >= 0 && SceneStamps.valid(tile.stamp, current);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        EventTile tile = selected;
        if (tile == null)
        {
            return null;
        }
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null || !drawable(tile, stamps.stamp(worldView.getPlane())))
        {
            return null;
        }
        Polygon poly = Perspective.getCanvasTilePoly(client, LocalPoint.fromScene(tile.sx, tile.sy, worldView));
        if (poly == null)
        {
            return null;
        }
        TilePainter.paint(graphics, poly, tile.getColor(), TeamTileOverlay.FILL_ALPHA, STROKE);
        return null;
    }
}
