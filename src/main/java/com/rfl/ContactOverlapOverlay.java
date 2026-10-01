package com.rfl;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.Area;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * While two players' bodies overlap, outlines each of their feet (from the posed model) on the
 * ground and fills where a foot of one crosses a foot of the other. Faint below the contact
 * threshold, the contact colour once the pair counts as a contact. Display only; contacts are
 * detected from {@link Body} parts.
 */
final class ContactOverlapOverlay extends Overlay
{
    private static final BasicStroke STROKE = new BasicStroke(1.5f);

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

        Map<String, Player> players = contactDetector.players();
        Color base = config.contactHighlightColor();
        graphics.setStroke(STROKE);
        for (ContactTracker.Overlap overlap : contactDetector.overlaps())
        {
            Player a = players.get(overlap.a);
            Player b = players.get(overlap.b);
            if (a == null || b == null)
            {
                continue;
            }
            int alpha = overlap.contact ? base.getAlpha() : base.getAlpha() / 3;
            Color line = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
            Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha / 2);

            Area feetA = drawFeet(graphics, worldView, a, line);
            Area feetB = drawFeet(graphics, worldView, b, line);
            // Projection keeps ground-plane shapes, so the screen intersection of the projected
            // feet is the projection of where the feet cross.
            feetA.intersect(feetB);
            graphics.setColor(fill);
            graphics.fill(feetA);
        }
        return null;
    }

    /** Outlines a player's feet on the ground and returns their combined projected area. */
    private Area drawFeet(Graphics2D graphics, WorldView worldView, Player player, Color line)
    {
        Area area = new Area();
        Model model = player.getModel();
        LocalPoint location = player.getLocalLocation();
        if (model == null || location == null)
        {
            return area;
        }

        int plane = worldView.getPlane();
        for (int[][] foot : Feet.footprints(model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(),
            model.getVerticesCount(), player.getCurrentOrientation(), location.getX(), location.getY()))
        {
            Polygon polygon = new Polygon();
            for (int[] corner : foot)
            {
                Point p = Perspective.localToCanvas(client, new LocalPoint(corner[0], corner[1], worldView), plane, 0);
                if (p == null)
                {
                    polygon = null;
                    break;
                }
                polygon.addPoint(p.getX(), p.getY());
            }
            if (polygon == null)
            {
                continue;
            }
            graphics.setColor(line);
            graphics.drawPolygon(polygon);
            area.add(new Area(polygon));
        }
        return area;
    }
}
