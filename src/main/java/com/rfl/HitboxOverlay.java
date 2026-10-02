package com.rfl;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
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
 * Fills the triangles where two players' meshes touch in the contact colour (Show touching
 * triangles), and with Show hitboxes also draws a wireframe of every player's posed mesh: the
 * exact triangles contacts are detected from. Display only. Reads the detector's latest frame;
 * while either is on, the tracker counts every touching pair each frame so the fill is complete.
 */
final class HitboxOverlay extends Overlay
{
    private static final BasicStroke STROKE = new BasicStroke(1f);

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
        boolean wireframe = config.showHitboxes();
        if (!wireframe && !config.showTouchingTriangles() || worldView == null)
        {
            return null;
        }

        graphics.setStroke(STROKE);
        int plane = worldView.getPlane();
        if (wireframe)
        {
            graphics.setColor(config.hitboxColor());
            for (PosedMesh mesh : contactDetector.meshes().values())
            {
                for (int t = 0; t < mesh.triangles; t++)
                {
                    Polygon triangle = triangle(worldView, plane, mesh, t);
                    if (triangle != null)
                    {
                        graphics.drawPolygon(triangle);
                    }
                }
            }
        }

        Color base = config.contactHighlightColor();
        Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), base.getAlpha() / 2);
        for (ContactTracker.Overlap overlap : contactDetector.overlaps())
        {
            PosedMesh.Hits hits = overlap.hits;
            for (int i = 0; hits != null && i < hits.count; i++)
            {
                fill(graphics, triangle(worldView, plane, hits.a, hits.pairs[i * 2]), base, fill);
                fill(graphics, triangle(worldView, plane, hits.b, hits.pairs[i * 2 + 1]), base, fill);
            }
        }
        return null;
    }

    /** A mesh triangle on screen, or null when a corner is off screen. */
    private Polygon triangle(WorldView worldView, int plane, PosedMesh mesh, int triangle)
    {
        Polygon polygon = new Polygon();
        for (int k = 0; k < 3; k++)
        {
            double[] c = mesh.corner(triangle, k);
            Point p = Perspective.localToCanvas(client,
                new LocalPoint((int) Math.round(c[0]), (int) Math.round(c[1]), worldView), plane, (int) Math.round(c[2]));
            if (p == null)
            {
                return null;
            }
            polygon.addPoint(p.getX(), p.getY());
        }
        return polygon;
    }

    private static void fill(Graphics2D graphics, Polygon triangle, Color line, Color fill)
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
