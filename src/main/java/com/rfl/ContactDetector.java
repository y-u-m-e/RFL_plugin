package com.rfl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;

import net.runelite.api.AABB;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.util.Text;

/**
 * Turns the live players in view into {@link Box} instances for {@link ContactTracker} every
 * client frame.
 *
 * Confirmed against runelite-api 1.12.39 via {@code javap} (the version {@code ./gradlew
 * dependencies --configuration compileClasspath} resolves for {@code latest.release}):
 * {@code Client#getTopLevelWorldView()}, {@code Client#getTickCount()},
 * {@code WorldView#players()}, {@code Actor#getModel()} (declared on {@code Renderable}, which
 * {@code Actor} extends), {@code Actor#getCurrentOrientation()}, {@code Actor#getLocalLocation()},
 * {@code Model#getAABB(int)} returning {@code AABB} with {@code getCenterX/Y/Z} and
 * {@code getExtremeX/Y/Z}.
 *
 * AABB semantics come from {@code net.runelite.api.Perspective#calculateAABB}, the one place in
 * the client jar that consumes an AABB: each axis is {@code center +/- extreme} (extreme is a
 * symmetric half-extent, not an absolute max). Model Y is vertical and negative-up; model X/Z
 * are the horizontal axes and match scene X/Y, so a player's box is built by adding
 * {@code LocalPoint.getX()/getY()} to the model's X/Z center-extreme and negating the model's Y
 * center-extreme into the box's Z.
 */
final class ContactDetector
{
    private final ContactTracker tracker = new ContactTracker();
    private final ContactHighlights highlights;

    @Inject
    ContactDetector(ContactHighlights highlights)
    {
        this.highlights = highlights;
    }

    List<RflEvent> onFrame(Client client)
    {
        WorldView worldView = client.getTopLevelWorldView();
        Map<String, Box> boxes = new HashMap<>();

        if (worldView != null)
        {
            for (Player player : worldView.players())
            {
                if (player == null)
                {
                    continue;
                }

                Box box = boxFor(player);
                String name = sanitizedName(player);
                if (box != null && name != null)
                {
                    boxes.put(name, box);
                }
            }
        }

        long now = System.currentTimeMillis();
        List<RflEvent> events = tracker.update(boxes, now, client.getTickCount());
        for (RflEvent event : events)
        {
            Box a = boxes.get(event.a);
            Box b = boxes.get(event.b);
            if ("contact_start".equals(event.type) && a != null && b != null)
            {
                int[] center = Box.overlapCenter(a, b);
                highlights.add(center[0], center[1], now);
            }
        }
        return events;
    }

    List<String> seen(Client client)
    {
        WorldView worldView = client.getTopLevelWorldView();
        Player local = client.getLocalPlayer();
        List<String> names = new ArrayList<>();

        if (worldView != null)
        {
            for (Player player : worldView.players())
            {
                if (player == null || player == local)
                {
                    continue;
                }

                String name = sanitizedName(player);
                if (name != null)
                {
                    names.add(name);
                }
            }
        }

        return names;
    }

    /**
     * Closes every currently open pair (an empty box map ends every active pair rather than
     * dropping it silently), for leaving the POH, a hop, or a logout while reporting is still
     * enabled. Callers that don't need the resulting {@code contact_end} events (reporting
     * disabled, plugin shutdown) should call {@link #reset()} instead.
     *
     * @param client client used only for its tick count; no player state is read
     * @return the contact_end events for every pair that was open
     */
    List<RflEvent> endAll(Client client)
    {
        return tracker.update(Collections.emptyMap(), System.currentTimeMillis(), client.getTickCount());
    }

    void reset()
    {
        tracker.reset();
        highlights.clear();
    }

    private static Box boxFor(Player player)
    {
        Model model = player.getModel();
        LocalPoint localPoint = player.getLocalLocation();
        if (model == null || localPoint == null)
        {
            return null;
        }

        AABB aabb = model.getAABB(player.getCurrentOrientation());
        if (aabb == null)
        {
            return null;
        }

        int minX = localPoint.getX() + aabb.getCenterX() - aabb.getExtremeX();
        int maxX = localPoint.getX() + aabb.getCenterX() + aabb.getExtremeX();
        int minY = localPoint.getY() + aabb.getCenterZ() - aabb.getExtremeZ();
        int maxY = localPoint.getY() + aabb.getCenterZ() + aabb.getExtremeZ();

        // Model Y is vertical and negative-up, so the box's up-positive Z center is -centerY;
        // the extreme is a symmetric half-extent so the sign flip alone is what matters.
        int zCenter = -aabb.getCenterY();
        int minZ = zCenter - aabb.getExtremeY();
        int maxZ = zCenter + aabb.getExtremeY();

        return new Box(minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static String sanitizedName(Player player)
    {
        String name = player.getName();
        return name == null ? null : Text.sanitize(name);
    }
}
