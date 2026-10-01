package com.rfl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.api.AABB;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.kit.KitType;
import net.runelite.client.util.Text;

/**
 * Turns the live players in view into {@link Cylinder} bodies for {@link ContactTracker} every
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
@Singleton
final class ContactDetector
{
    private final ContactTracker tracker = new ContactTracker();
    private final ContactHighlights highlights;
    private Map<String, Cylinder> latestBodies = Collections.emptyMap();

    @Inject
    ContactDetector(ContactHighlights highlights)
    {
        this.highlights = highlights;
    }

    List<RflEvent> onFrame(Client client)
    {
        WorldView worldView = client.getTopLevelWorldView();
        Map<String, Cylinder> bodies = new HashMap<>();

        if (worldView != null)
        {
            for (Player player : worldView.players())
            {
                if (player == null)
                {
                    continue;
                }

                Cylinder body = bodyFor(player);
                String name = sanitizedName(player);
                if (body != null && name != null)
                {
                    bodies.put(name, body);
                }
            }
        }

        long now = System.currentTimeMillis();
        latestBodies = bodies;
        List<RflEvent> events = tracker.update(bodies, now, client.getTickCount());
        for (RflEvent event : events)
        {
            Cylinder a = bodies.get(event.a);
            Cylinder b = bodies.get(event.b);
            if ("contact_start".equals(event.type) && a != null && b != null)
            {
                int[] center = Cylinder.overlapCenter(a, b);
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
        latestBodies = Collections.emptyMap();
    }

    /** Bodies from the latest frame, by sanitized name. */
    Map<String, Cylinder> bodies()
    {
        return latestBodies;
    }

    List<ContactTracker.Overlap> overlaps()
    {
        return tracker.overlaps();
    }

    Map<String, List<String>> contactsByPlayer()
    {
        return tracker.contactsByPlayer();
    }

    /** Sanitized names of players with a handegg in the weapon slot. */
    Set<String> handeggHolders(Client client)
    {
        WorldView worldView = client.getTopLevelWorldView();
        Set<String> holders = new HashSet<>();
        if (worldView == null)
        {
            return holders;
        }
        for (Player player : worldView.players())
        {
            PlayerComposition composition = player == null ? null : player.getPlayerComposition();
            String name = player == null ? null : sanitizedName(player);
            if (composition != null && name != null
                && InterceptionDetector.HANDEGG_ITEMS.contains(composition.getEquipmentId(KitType.WEAPON)))
            {
                holders.add(name);
            }
        }
        return holders;
    }

    /**
     * The player's body as an upright cylinder. The radius comes from the model's unrotated
     * bounds (average of its half-width and half-depth), so it doesn't grow when the player turns;
     * the centre and height come from the bounds at the current orientation.
     */
    private static Cylinder bodyFor(Player player)
    {
        Model model = player.getModel();
        LocalPoint localPoint = player.getLocalLocation();
        if (model == null || localPoint == null)
        {
            return null;
        }

        AABB rotated = model.getAABB(player.getCurrentOrientation());
        AABB upright = model.getAABB(0);
        if (rotated == null || upright == null)
        {
            return null;
        }

        int radius = (upright.getExtremeX() + upright.getExtremeZ()) / 2;
        int x = localPoint.getX() + rotated.getCenterX();
        int y = localPoint.getY() + rotated.getCenterZ();

        // Model Y is vertical and negative-up, so the up-positive centre is -centerY.
        int zCenter = -rotated.getCenterY();
        return new Cylinder(x, y, radius, zCenter - rotated.getExtremeY(), zCenter + rotated.getExtremeY());
    }

    private static String sanitizedName(Player player)
    {
        String name = player.getName();
        return name == null ? null : Text.sanitize(name);
    }
}
