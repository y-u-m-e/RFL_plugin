package com.rfl;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/** Draws a fading tile under each recent contact point. Display only. */
final class ContactHighlightOverlay extends Overlay
{
    private static final BasicStroke STROKE = new BasicStroke(2);

    private final Client client;
    private final RflConfig config;
    private final ContactHighlights highlights;

    @Inject
    ContactHighlightOverlay(Client client, RflConfig config, ContactHighlights highlights)
    {
        this.client = client;
        this.config = config;
        this.highlights = highlights;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null)
        {
            return null;
        }

        for (ContactHighlights.Highlight h : highlights.active(System.currentTimeMillis(), config.highlightDurationMs()))
        {
            if (!h.interception && !config.highlightContacts())
            {
                continue;
            }
            Color base = h.interception ? config.interceptionColor() : config.contactHighlightColor();
            Polygon tile = Perspective.getCanvasTilePoly(client, new LocalPoint(h.x, h.y, worldView));
            if (tile == null)
            {
                continue;
            }
            int alpha = Math.round(base.getAlpha() * h.alpha);
            Color border = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
            Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha / 3);
            OverlayUtil.renderPolygon(graphics, tile, border, fill, STROKE);
        }
        return null;
    }
}
