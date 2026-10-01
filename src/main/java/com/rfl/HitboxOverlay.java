package com.rfl;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 * Draws every body-part capsule of every player in view: a horizontal ring at each endpoint and
 * four lines joining them. Parts in a contact use the contact colour. Display only.
 */
final class HitboxOverlay extends Overlay
{
    private static final BasicStroke STROKE = new BasicStroke(1f);
    private static final int RING_POINTS = 12;

    private final Client client;
    private final RflConfig config;
    private final ContactDetector contactDetector;

    @Inject
    HitboxOverlay(Client client, RflConfig config, ContactDetector contactDetector)
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
        WorldView worldView = client.getTopLevelWorldView();
        if (!config.showHitboxes() || worldView == null)
        {
            return null;
        }

        Set<String> inContact = new HashSet<>();
        for (ContactTracker.Overlap overlap : contactDetector.overlaps())
        {
            if (overlap.contact)
            {
                inContact.add(overlap.a + '\u0000' + overlap.partA);
                inContact.add(overlap.b + '\u0000' + overlap.partB);
            }
        }

        graphics.setStroke(STROKE);
        int plane = worldView.getPlane();
        for (Map.Entry<String, Body> entry : contactDetector.bodies().entrySet())
        {
            for (Capsule part : entry.getValue().parts)
            {
                graphics.setColor(inContact.contains(entry.getKey() + '\u0000' + part.name)
                    ? config.contactHighlightColor() : config.hitboxColor());
                Point[] ringA = ring(worldView, plane, part.ax, part.ay, part.az, part.radius);
                Point[] ringB = ring(worldView, plane, part.bx, part.by, part.bz, part.radius);
                drawRing(graphics, ringA);
                drawRing(graphics, ringB);
                for (int i = 0; i < RING_POINTS; i += RING_POINTS / 4)
                {
                    if (ringA[i] != null && ringB[i] != null)
                    {
                        graphics.drawLine(ringA[i].getX(), ringA[i].getY(), ringB[i].getX(), ringB[i].getY());
                    }
                }
            }
        }
        return null;
    }

    private Point[] ring(WorldView worldView, int plane, double x, double y, double height, double radius)
    {
        Point[] points = new Point[RING_POINTS];
        for (int i = 0; i < RING_POINTS; i++)
        {
            double angle = 2 * Math.PI * i / RING_POINTS;
            LocalPoint lp = new LocalPoint((int) Math.round(x + radius * Math.cos(angle)),
                (int) Math.round(y + radius * Math.sin(angle)), worldView);
            points[i] = Perspective.localToCanvas(client, lp, plane, (int) Math.round(height));
        }
        return points;
    }

    private static void drawRing(Graphics2D graphics, Point[] ring)
    {
        for (int i = 0; i < ring.length; i++)
        {
            Point a = ring[i];
            Point b = ring[(i + 1) % ring.length];
            if (a != null && b != null)
            {
                graphics.drawLine(a.getX(), a.getY(), b.getX(), b.getY());
            }
        }
    }
}
