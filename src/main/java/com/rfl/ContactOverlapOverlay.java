package com.rfl;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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
 * While two players' bodies overlap, outlines each of their feet and fills where a foot of one
 * crosses a foot of the other on screen. The feet are the same foot capsules the hitboxes and
 * contact check use, so a foot lifted or kicked back is drawn where it is. Faint below the contact
 * threshold, the contact colour once the pair counts as a contact. Display only.
 */
final class ContactOverlapOverlay extends Overlay
{
    private static final BasicStroke STROKE = new BasicStroke(1.5f);
    private static final int RING_POINTS = 12;

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

        Map<String, Body> bodies = contactDetector.bodies();
        Color base = config.contactHighlightColor();
        graphics.setStroke(STROKE);
        for (ContactTracker.Overlap overlap : contactDetector.overlaps())
        {
            Body a = bodies.get(overlap.a);
            Body b = bodies.get(overlap.b);
            if (a == null || b == null)
            {
                continue;
            }
            int alpha = overlap.contact ? base.getAlpha() : base.getAlpha() / 3;
            Color line = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
            Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha / 2);

            Area feetA = drawFeet(graphics, worldView, a, line);
            Area feetB = drawFeet(graphics, worldView, b, line);
            feetA.intersect(feetB);
            graphics.setColor(fill);
            graphics.fill(feetA);
        }
        return null;
    }

    /** Outlines a body's foot capsules on screen and returns their combined outline area. */
    private Area drawFeet(Graphics2D graphics, WorldView worldView, Body body, Color line)
    {
        Area area = new Area();
        int plane = worldView.getPlane();
        for (Capsule part : body.parts)
        {
            if (!part.name.endsWith("Foot"))
            {
                continue;
            }
            double[] axis = {part.bx - part.ax, part.by - part.ay, part.bz - part.az};
            List<double[]> ring = new ArrayList<>();
            ring.addAll(Arrays.asList(HitboxOverlay.ringPoints(
                new double[]{part.ax, part.ay, part.az}, axis, part.radius, RING_POINTS)));
            ring.addAll(Arrays.asList(HitboxOverlay.ringPoints(
                new double[]{part.bx, part.by, part.bz}, axis, part.radius, RING_POINTS)));

            int[][] screen = new int[ring.size()][];
            boolean visible = true;
            for (int i = 0; i < ring.size(); i++)
            {
                double[] p = ring.get(i);
                Point s = Perspective.localToCanvas(client,
                    new LocalPoint((int) Math.round(p[0]), (int) Math.round(p[1]), worldView), plane,
                    (int) Math.round(p[2]));
                if (s == null)
                {
                    visible = false;
                    break;
                }
                screen[i] = new int[]{s.getX(), s.getY()};
            }
            if (!visible)
            {
                continue;
            }

            Polygon outline = new Polygon();
            for (int[] p : convexHull(screen))
            {
                outline.addPoint(p[0], p[1]);
            }
            graphics.setColor(line);
            graphics.drawPolygon(outline);
            area.add(new Area(outline));
        }
        return area;
    }

    /** Convex hull of 2D points (monotone chain), counter-clockwise; fewer than 3 points as given. */
    static int[][] convexHull(int[][] points)
    {
        if (points.length < 3)
        {
            return points;
        }
        int[][] sorted = points.clone();
        Arrays.sort(sorted, Comparator.<int[]>comparingInt(p -> p[0]).thenComparingInt(p -> p[1]));
        int[][] hull = new int[2 * sorted.length][];
        int k = 0;
        for (int[] p : sorted)
        {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], p) <= 0)
            {
                k--;
            }
            hull[k++] = p;
        }
        for (int i = sorted.length - 2, lower = k + 1; i >= 0; i--)
        {
            int[] p = sorted[i];
            while (k >= lower && cross(hull[k - 2], hull[k - 1], p) <= 0)
            {
                k--;
            }
            hull[k++] = p;
        }
        return Arrays.copyOf(hull, k - 1);
    }

    private static long cross(int[] o, int[] a, int[] b)
    {
        return (long) (a[0] - o[0]) * (b[1] - o[1]) - (long) (a[1] - o[1]) * (b[0] - o[0]);
    }
}
