package com.rfl;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
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
 * Draws every body-part capsule of every player in view: a ring square to the part at each endpoint and
 * four lines joining them. Parts in a contact use the contact colour; triangles of two models that
 * intersect (mesh contact modes) are filled red. Display only.
 */
final class HitboxOverlay extends Overlay
{
    private static final BasicStroke STROKE = new BasicStroke(1f);
    private static final int RING_POINTS = 12;
    private static final Color MESH_HIT_FILL = new Color(255, 0, 0, 90);

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
        RflConfig.HitboxView view = config.hitboxView();
        if (view != RflConfig.HitboxView.CAPSULES)
        {
            Color base = config.hitboxColor();
            graphics.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.max(30, base.getAlpha() / 2)));
            for (Body body : contactDetector.bodies().values())
            {
                if (body.mesh != null)
                {
                    drawMesh(graphics, worldView, plane, body.mesh);
                }
            }
        }
        for (Map.Entry<String, Body> entry : contactDetector.bodies().entrySet())
        {
            if (view == RflConfig.HitboxView.MESH)
            {
                break;
            }
            for (Capsule part : entry.getValue().parts)
            {
                graphics.setColor(inContact.contains(entry.getKey() + '\u0000' + part.name)
                    ? config.contactHighlightColor() : config.hitboxColor());
                double[] axis = {part.bx - part.ax, part.by - part.ay, part.bz - part.az};
                Point[] ringA = project(worldView, plane,
                    ringPoints(new double[]{part.ax, part.ay, part.az}, axis, part.radius, RING_POINTS));
                Point[] ringB = project(worldView, plane,
                    ringPoints(new double[]{part.bx, part.by, part.bz}, axis, part.radius, RING_POINTS));
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

        graphics.setColor(MESH_HIT_FILL);
        for (PosedMesh.Hits hits : contactDetector.meshHits())
        {
            for (int i = 0; i < hits.count; i++)
            {
                fill(graphics, triangle(client, worldView, plane, hits.a, hits.pairs[i * 2]));
                fill(graphics, triangle(client, worldView, plane, hits.b, hits.pairs[i * 2 + 1]));
            }
        }
        return null;
    }

    /** Outlines every triangle of the posed mesh: the exact surface the mesh contact modes test. */
    private void drawMesh(Graphics2D graphics, WorldView worldView, int plane, PosedMesh mesh)
    {
        int[] xs = new int[3];
        int[] ys = new int[3];
        for (int t = 0; t < mesh.triangles; t++)
        {
            boolean visible = true;
            for (int k = 0; k < 3 && visible; k++)
            {
                double[] c = mesh.corner(t, k);
                Point p = Perspective.localToCanvas(client,
                    new LocalPoint((int) Math.round(c[0]), (int) Math.round(c[1]), worldView), plane,
                    (int) Math.round(c[2]));
                if (p == null)
                {
                    visible = false;
                }
                else
                {
                    xs[k] = p.getX();
                    ys[k] = p.getY();
                }
            }
            if (visible)
            {
                graphics.drawPolygon(xs, ys, 3);
            }
        }
    }

    /** A mesh triangle on screen, or null when a corner is off screen. */
    static Polygon triangle(Client client, WorldView worldView, int plane, PosedMesh mesh, int triangle)
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

    private static void fill(Graphics2D graphics, Polygon polygon)
    {
        if (polygon != null)
        {
            graphics.fill(polygon);
        }
    }

    /**
     * Points of a circle of the given radius around {@code centre}, in the plane perpendicular to
     * {@code axis}: an upright part gets flat rings, a lying part (a foot) gets standing ones.
     * Coordinates are scene {x, y, height}.
     */
    static double[][] ringPoints(double[] centre, double[] axis, double radius, int count)
    {
        double len = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        double[] d = len < 1e-9 ? new double[]{0, 0, 1} : new double[]{axis[0] / len, axis[1] / len, axis[2] / len};
        double[] ref = Math.abs(d[2]) < 0.9 ? new double[]{0, 0, 1} : new double[]{1, 0, 0};
        double[] u = unit(cross(d, ref));
        double[] v = cross(d, u);
        double[][] points = new double[count][3];
        for (int i = 0; i < count; i++)
        {
            double angle = 2 * Math.PI * i / count;
            double c = Math.cos(angle) * radius;
            double s = Math.sin(angle) * radius;
            for (int k = 0; k < 3; k++)
            {
                points[i][k] = centre[k] + c * u[k] + s * v[k];
            }
        }
        return points;
    }

    private Point[] project(WorldView worldView, int plane, double[][] ring)
    {
        Point[] points = new Point[ring.length];
        for (int i = 0; i < ring.length; i++)
        {
            LocalPoint lp = new LocalPoint((int) Math.round(ring[i][0]), (int) Math.round(ring[i][1]), worldView);
            points[i] = Perspective.localToCanvas(client, lp, plane, (int) Math.round(ring[i][2]));
        }
        return points;
    }

    private static double[] cross(double[] a, double[] b)
    {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] unit(double[] a)
    {
        double len = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[]{a[0] / len, a[1] / len, a[2] / len};
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
