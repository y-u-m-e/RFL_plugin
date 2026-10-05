package com.rfl.overlay;

import com.rfl.RflConfig;
import com.rfl.contact.ContactDetector;
import com.rfl.contact.ContactTracker;
import sh.yumekui.toolkit.geom.TriangleMesh;
import sh.yumekui.toolkit.overlay.MeshProjector;
import sh.yumekui.toolkit.overlay.TilePainter;
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
 * while either is on, the tracker takes the full touching count on every handegg pair each frame
 * so the fill is complete. Pairs with no handegg holder are never triangle-checked, so they never
 * fill here either.
 *
 * <p>Each mesh's vertices are projected at most once per frame ({@link MeshProjector}). Client
 * thread only, like every overlay render.
 */
public final class HitboxOverlay extends Overlay
{
    private static final BasicStroke STROKE = new BasicStroke(1f);
    /** The touching-triangle fill is the contact colour at this share of its alpha. */
    private static final int FILL_ALPHA_DIVISOR = 2;

    private final Client client;
    private final RflConfig config;
    private final ContactDetector contactDetector;
    private final MeshProjector projector = new MeshProjector(this::project);
    private final Polygon triangle = new Polygon();
    /** The world view and plane of the frame being drawn, for {@link #project}. */
    private WorldView worldView;
    private int plane;

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
        boolean wireframe = config.showHitboxes();
        worldView = client.getTopLevelWorldView();
        if (!wireframe && !config.showTouchingTriangles() || worldView == null)
        {
            return null;
        }
        plane = worldView.getPlane();
        projector.startFrame();
        graphics.setStroke(STROKE);
        if (wireframe)
        {
            drawWireframes(graphics);
        }
        fillTouching(graphics);
        return null;
    }

    private void drawWireframes(Graphics2D graphics)
    {
        graphics.setColor(config.hitboxColor());
        for (TriangleMesh mesh : contactDetector.meshes().values())
        {
            for (int t = 0; t < mesh.triangles; t++)
            {
                if (projector.triangle(mesh, t, triangle))
                {
                    graphics.drawPolygon(triangle);
                }
            }
        }
    }

    private void fillTouching(Graphics2D graphics)
    {
        Color line = config.contactHighlightColor();
        Color fill = TilePainter.withAlpha(line, line.getAlpha() / FILL_ALPHA_DIVISOR);
        for (ContactTracker.Overlap overlap : contactDetector.overlaps())
        {
            TriangleMesh.Hits hits = overlap.hits;
            for (int i = 0; hits != null && i < hits.count; i++)
            {
                fillIfOnScreen(graphics, hits.a, hits.pairs[i * 2], line, fill);
                fillIfOnScreen(graphics, hits.b, hits.pairs[i * 2 + 1], line, fill);
            }
        }
    }

    private void fillIfOnScreen(Graphics2D graphics, TriangleMesh mesh, int t, Color line, Color fill)
    {
        if (!projector.triangle(mesh, t, triangle))
        {
            return;
        }
        graphics.setColor(fill);
        graphics.fill(triangle);
        graphics.setColor(line);
        graphics.drawPolygon(triangle);
    }

    /** A mesh vertex to the canvas, rounded through double exactly as the per-corner projection did. */
    private boolean project(float x, float y, float z, int[] out)
    {
        LocalPoint at = new LocalPoint((int) Math.round((double) x), (int) Math.round((double) y), worldView);
        Point point = Perspective.localToCanvas(client, at, plane, (int) Math.round((double) z));
        if (point == null)
        {
            return false;
        }
        out[0] = point.getX();
        out[1] = point.getY();
        return true;
    }
}
