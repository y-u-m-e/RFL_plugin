package sh.yumekui.toolkit.overlay;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import net.runelite.client.ui.overlay.OverlayUtil;

/** Outlined, lightly filled tiles, the usual way overlays mark a tile. */
public final class TilePainter
{
    /** The largest alpha, fully opaque. */
    private static final int OPAQUE = 255;

    private TilePainter()
    {
    }

    /** {@code color} with its alpha replaced, clamped to 0-255. */
    public static Color withAlpha(Color color, int alpha)
    {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(OPAQUE, alpha)));
    }

    /**
     * Outlines {@code tile} in {@code outline} and fills it in the same colour at {@code fillAlpha}
     * (0-255), so the tile shows without hiding what stands on it.
     */
    public static void paint(Graphics2D graphics, Polygon tile, Color outline, int fillAlpha, Stroke stroke)
    {
        OverlayUtil.renderPolygon(graphics, tile, outline, withAlpha(outline, fillAlpha), stroke);
    }
}
