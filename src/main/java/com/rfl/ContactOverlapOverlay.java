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
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * While two players' bodies overlap, draws a short cylinder around each player's feet and fills
 * where their footprints cross. Faint below the contact threshold, the contact colour once the
 * pair counts as a contact. Display only.
 */
final class ContactOverlapOverlay extends Overlay
{
    /** Ring points per footprint; enough to look round at normal zoom. */
    static final int RING_POINTS = 24;
    /** Height of the drawn feet cylinder, in local units. */
    private static final int ANKLE_HEIGHT = 40;
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

    /** Scene points of a footprint ring, evenly spaced, starting at angle 0. */
    static int[][] ringPoints(Cylinder body, int count)
    {
        int[][] points = new int[count][2];
        for (int i = 0; i < count; i++)
        {
            double angle = 2 * Math.PI * i / count;
            points[i][0] = (int) Math.round(body.x + body.radius * Math.cos(angle));
            points[i][1] = (int) Math.round(body.y + body.radius * Math.sin(angle));
        }
        return points;
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

        Map<String, Cylinder> bodies = contactDetector.bodies();
        Color base = config.contactHighlightColor();
        for (ContactTracker.Overlap overlap : contactDetector.overlaps())
        {
            Cylinder a = bodies.get(overlap.a);
            Cylinder b = bodies.get(overlap.b);
            if (a == null || b == null)
            {
                continue;
            }
            int alpha = overlap.contact ? base.getAlpha() : base.getAlpha() / 3;
            Color line = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
            Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha / 2);

            Polygon groundA = drawFeet(graphics, worldView, a, line);
            Polygon groundB = drawFeet(graphics, worldView, b, line);
            if (groundA != null && groundB != null)
            {
                // Projection keeps the ground plane's shapes, so the screen intersection of the two
                // projected rings is the projection of where the footprints cross.
                Area crossing = new Area(groundA);
                crossing.intersect(new Area(groundB));
                graphics.setColor(fill);
                graphics.fill(crossing);
            }
        }
        return null;
    }

    /** Draws one feet cylinder and returns its projected ground ring, or null if off-screen. */
    private Polygon drawFeet(Graphics2D graphics, WorldView worldView, Cylinder body, Color line)
    {
        int plane = worldView.getPlane();
        Polygon ground = new Polygon();
        Polygon ankle = new Polygon();
        for (int[] p : ringPoints(body, RING_POINTS))
        {
            LocalPoint lp = new LocalPoint(p[0], p[1], worldView);
            Point g = Perspective.localToCanvas(client, lp, plane, 0);
            Point t = Perspective.localToCanvas(client, lp, plane, ANKLE_HEIGHT);
            if (g == null || t == null)
            {
                return null;
            }
            ground.addPoint(g.getX(), g.getY());
            ankle.addPoint(t.getX(), t.getY());
        }

        graphics.setStroke(STROKE);
        graphics.setColor(line);
        graphics.drawPolygon(ground);
        graphics.drawPolygon(ankle);
        for (int i = 0; i < RING_POINTS; i += RING_POINTS / 4)
        {
            graphics.drawLine(ground.xpoints[i], ground.ypoints[i], ankle.xpoints[i], ankle.ypoints[i]);
        }
        return ground;
    }
}
