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

import lombok.extern.slf4j.Slf4j;
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
 * client frame, from each player's posed model vertices: the drawn model, or the bare body
 * ({@link BareBody}) per the Hitbox source setting.
 */
@Slf4j
@Singleton
final class ContactDetector
{
    private static final long LOG_INTERVAL_MS = 10_000;

    private final ContactTracker tracker = new ContactTracker();
    private final ContactHighlights highlights;
    private final RflConfig config;
    private final BareBody bareBody;
    private Map<String, Body> latestBodies = Collections.emptyMap();
    private Map<String, Player> latestPlayers = Collections.emptyMap();
    private List<String> latestMissing = Collections.emptyList();

    // Mesh cost (building the posed triangles plus checking them), averaged every ~10 s.
    private long meshNanos;
    private int meshFrames;
    private long meshLogAt;
    private volatile double meshMsPerFrame;

    @Inject
    ContactDetector(ContactHighlights highlights, RflConfig config, BareBody bareBody)
    {
        this.highlights = highlights;
        this.config = config;
        this.bareBody = bareBody;
    }

    @Inject
    private ModelDumper modelDumper;

    List<RflEvent> onFrame(Client client)
    {
        WorldView worldView = client.getTopLevelWorldView();
        Map<String, Body> bodies = new HashMap<>();
        Map<String, Player> players = new HashMap<>();
        List<String> missing = new ArrayList<>();
        boolean bare = config.hitboxSource() == RflConfig.HitboxSource.BARE_BODY;
        RflConfig.ContactMode mode = config.contactMode();
        boolean withMesh = mode != RflConfig.ContactMode.CAPSULES;
        tracker.setMode(mode);

        if (worldView != null)
        {
            for (Player player : worldView.players())
            {
                if (player == null)
                {
                    continue;
                }

                Body body = bodyFor(client, player, bare, withMesh);
                String name = sanitizedName(player);
                if (body != null && name != null)
                {
                    bodies.put(name, body);
                    players.put(name, player);
                }
                else if (name != null)
                {
                    missing.add(name);
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
        latestMissing = missing;
        List<RflEvent> events = tracker.update(bodies, now, client.getTickCount());
        endMeshFrame(now, withMesh);
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

    /** Intersecting triangle pairs from the latest frame; client thread only. */
    List<PosedMesh.Hits> meshHits()
    {
        return tracker.meshHits();
    }

    /** Average ms per frame spent on mesh work over the last ~10 s window. */
    double meshMsPerFrame()
    {
        return meshMsPerFrame;
    }

    private void endMeshFrame(long now, boolean withMesh)
    {
        meshNanos += tracker.takeMeshNanos();
        meshFrames++;
        if (now - meshLogAt < LOG_INTERVAL_MS)
        {
            return;
        }
        meshMsPerFrame = meshNanos / 1e6 / meshFrames;
        if (withMesh && config.debugLogging() && meshLogAt != 0)
        {
            log.info("[RFL debug] mesh contacts: {} ms/frame avg over {} frames ({} bodies)",
                String.format("%.3f", meshMsPerFrame), meshFrames, latestBodies.size());
        }
        meshNanos = 0;
        meshFrames = 0;
        meshLogAt = now;
    }

    Map<String, List<String>> collidingNow()
    {
        return tracker.collidingNow();
    }

    /** Names of players in view whose body could not be built in the latest frame. */
    List<String> missingBodies()
    {
        return latestMissing;
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
    private Body bodyFor(Client client, Player player, boolean bare, boolean withMesh)
    {
        Model model = bare ? bareBody.posed(player) : player.getModel();
        LocalPoint localPoint = player.getLocalLocation();
        if (model == null || localPoint == null)
        {
            return null;
        }
        Body body = Body.from(model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(),
            model.getVerticesCount(), player.getCurrentOrientation(), localPoint.getX(), localPoint.getY());
        if (withMesh && model.getFaceIndices1() != null && model.getFaceIndices2() != null
            && model.getFaceIndices3() != null)
        {
            long start = System.nanoTime();
            body = body.withMesh(PosedMesh.from(model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(),
                model.getVerticesCount(), model.getFaceIndices1(), model.getFaceIndices2(), model.getFaceIndices3(),
                model.getFaceCount(), model.getFaceTransparencies(), model.getFaceColors3(),
                player.getCurrentOrientation(), localPoint.getX(), localPoint.getY()));
            meshNanos += System.nanoTime() - start;
        }
        if (config.debugLogging() && player == client.getLocalPlayer())
        {
            modelDumper.maybeDump(client.getTickCount(), sanitizedName(player), bare, player.getCurrentOrientation(),
                player.getAnimation(), player.getPoseAnimation(), model.getVerticesX(), model.getVerticesY(),
                model.getVerticesZ(), model.getVerticesCount(), localPoint.getX(), localPoint.getY(), body);
        }
        return body;
    }

    private static String sanitizedName(Player player)
    {
        String name = player.getName();
        return name == null ? null : Text.sanitize(name);
    }
}
