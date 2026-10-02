package com.rfl;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.WorldView;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * While two players' bodies overlap, fills and outlines the model triangles of each where the two
 * meshes touch: the exact surface the mesh check sees, in every contact mode. Faint below the
 * contact threshold, the contact colour once the pair counts as a contact. Display only.
 */
final class ContactOverlapOverlay extends Overlay
{
    private static final BasicStroke STROKE = new BasicStroke(1f);

    private final Client client;
    private final RflConfig config;
    private final ContactDetector contactDetector;

    @Inject
    ContactOverlapOverlay(Client client, RflConfig config, ContactDetector contactDetector)
    {
        this.client = client;
        this.config = config;
        this.contactDetector = contactDetector;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showOverlap())
        {
            return null;
        }
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null)
        {
            return null;
        }

        int plane = worldView.getPlane();
        Color base = config.contactHighlightColor();
        graphics.setStroke(STROKE);
        for (ContactTracker.Overlap overlap : contactDetector.overlaps())
        {
            PosedMesh.Hits hits = overlap.hits;
            if (hits == null)
            {
                continue;
            }
            int alpha = base.getAlpha();
            Color line = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
            Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha / 2);
            for (int i = 0; i < hits.count; i++)
            {
                draw(graphics, HitboxOverlay.triangle(client, worldView, plane, hits.a, hits.pairs[i * 2]), line, fill);
                draw(graphics, HitboxOverlay.triangle(client, worldView, plane, hits.b, hits.pairs[i * 2 + 1]), line, fill);
            }
        }
        return null;
    }

    private static void draw(Graphics2D graphics, Polygon triangle, Color line, Color fill)
    {
        if (triangle == null)
        {
            return;
        }
        graphics.setColor(fill);
        graphics.fill(triangle);
        graphics.setColor(line);
        graphics.drawPolygon(triangle);
    }
}
