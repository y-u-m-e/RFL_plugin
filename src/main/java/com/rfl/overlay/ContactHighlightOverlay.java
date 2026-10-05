package com.rfl.overlay;

import sh.yumekui.toolkit.overlay.TilePainter;
import com.rfl.RflConfig;
import com.rfl.contact.ContactHighlights;

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

/** Draws a fading tile under each recent contact point. Display only. */
public final class ContactHighlightOverlay extends Overlay
{
    private static final BasicStroke STROKE = new BasicStroke(2);
    /** The fill is a third as opaque as the fading outline. */
    private static final int FILL_ALPHA_DIVISOR = 3;

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

        for (ContactHighlights.Highlight highlight : highlights.active(System.currentTimeMillis(), config.highlightDurationMs()))
        {
            if (!highlight.incomplete && !config.highlightContacts())
            {
                continue;
            }
            Color base = highlight.incomplete ? config.incompleteColor() : config.contactHighlightColor();
            Polygon tile = Perspective.getCanvasTilePoly(client, new LocalPoint(highlight.x, highlight.y, worldView));
            if (tile == null)
            {
                continue;
            }
            int alpha = Math.round(base.getAlpha() * highlight.alpha);
            TilePainter.paint(graphics, tile, TilePainter.withAlpha(base, alpha), alpha / FILL_ALPHA_DIVISOR, STROKE);
        }
        return null;
    }
}
