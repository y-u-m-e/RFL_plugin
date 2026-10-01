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

import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.kit.KitType;
import net.runelite.client.util.Text;

/**
 * Turns the live players in view into {@link Body} part capsules for {@link ContactTracker} every
 * client frame, from each player's posed model vertices (same reading and rotation as
 * {@link Feet}): the drawn model, or the bare body ({@link BareBody}) per the Hitbox source setting.
 */
@Singleton
final class ContactDetector
{
    private final ContactTracker tracker = new ContactTracker();
    private final ContactHighlights highlights;
    private final RflConfig config;
    private final BareBody bareBody;
    private Map<String, Body> latestBodies = Collections.emptyMap();
    private Map<String, Player> latestPlayers = Collections.emptyMap();

    @Inject
    ContactDetector(ContactHighlights highlights, RflConfig config, BareBody bareBody)
    {
        this.highlights = highlights;
        this.config = config;
        this.bareBody = bareBody;
    }

    List<RflEvent> onFrame(Client client)
    {
        WorldView worldView = client.getTopLevelWorldView();
        Map<String, Body> bodies = new HashMap<>();
        Map<String, Player> players = new HashMap<>();
        boolean bare = config.hitboxSource() == RflConfig.HitboxSource.BARE_BODY;

        if (worldView != null)
        {
            for (Player player : worldView.players())
            {
                if (player == null)
                {
                    continue;
                }

                Body body = bodyFor(player, bare);
                String name = sanitizedName(player);
                if (body != null && name != null)
                {
                    bodies.put(name, body);
                    players.put(name, player);
                }
            }
        }

        if (bare)
        {
            bareBody.endFrame();
        }

        long now = System.currentTimeMillis();
        latestBodies = bodies;
        latestPlayers = players;
        List<RflEvent> events = tracker.update(bodies, now, client.getTickCount());
        for (RflEvent event : events)
        {
            Body a = bodies.get(event.a);
            Body b = bodies.get(event.b);
            Body.Contact contact = "contact_start".equals(event.type) && a != null && b != null
                ? Body.contact(a, b) : null;
            if (contact != null)
            {
                highlights.add(contact.x, contact.y, now);
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
        latestBodies = Collections.emptyMap();
        latestPlayers = Collections.emptyMap();
        return tracker.update(Collections.emptyMap(), System.currentTimeMillis(), client.getTickCount());
    }

    void reset()
    {
        tracker.reset();
        highlights.clear();
        bareBody.reset();
        latestBodies = Collections.emptyMap();
        latestPlayers = Collections.emptyMap();
    }

    /** Players from the latest frame, by sanitized name; read on the client thread only. */
    Map<String, Player> players()
    {
        return latestPlayers;
    }

    /** Bodies from the latest frame, by sanitized name. */
    Map<String, Body> bodies()
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

    /** Weapon-slot item id of every player in view, by sanitized name (-1 for empty). */
    Map<String, Integer> weapons(Client client)
    {
        WorldView worldView = client.getTopLevelWorldView();
        Map<String, Integer> weapons = new HashMap<>();
        if (worldView == null)
        {
            return weapons;
        }
        for (Player player : worldView.players())
        {
            PlayerComposition composition = player == null ? null : player.getPlayerComposition();
            String name = player == null ? null : sanitizedName(player);
            if (composition != null && name != null)
            {
                weapons.put(name, composition.getEquipmentId(KitType.WEAPON));
            }
        }
        return weapons;
    }

    /** Sanitized names of players with a handegg in the weapon slot. */
    static Set<String> handeggHolders(Map<String, Integer> weapons)
    {
        Set<String> holders = new HashSet<>();
        for (Map.Entry<String, Integer> entry : weapons.entrySet())
        {
            if (InterceptionDetector.HANDEGG_ITEMS.contains(entry.getValue()))
            {
                holders.add(entry.getKey());
            }
        }
        return holders;
    }

    /** Body from the player's drawn model, or their bare body per the Hitbox source setting. */
    private Body bodyFor(Player player, boolean bare)
    {
        Model model = bare ? bareBody.posed(player) : player.getModel();
        LocalPoint localPoint = player.getLocalLocation();
        if (model == null || localPoint == null)
        {
            return null;
        }
        return Body.from(model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(), model.getVerticesCount(),
            player.getCurrentOrientation(), localPoint.getX(), localPoint.getY());
    }

    private static String sanitizedName(Player player)
    {
        String name = player.getName();
        return name == null ? null : Text.sanitize(name);
    }
}
