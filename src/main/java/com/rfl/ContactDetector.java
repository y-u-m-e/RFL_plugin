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
 * Turns the live players in view into {@link PosedMesh} triangles for {@link ContactTracker} every
 * client frame, from each player's posed model: the drawn model, or the bare body
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
    private Map<String, PosedMesh> latestMeshes = Collections.emptyMap();
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

    List<RflEvent> onFrame(Client client)
    {
        WorldView worldView = client.getTopLevelWorldView();
        Map<String, PosedMesh> meshes = new HashMap<>();
        Map<String, Player> players = new HashMap<>();
        List<String> missing = new ArrayList<>();
        boolean bare = config.hitboxSource() == RflConfig.HitboxSource.BARE_BODY;

        if (worldView != null)
        {
            for (Player player : worldView.players())
            {
                if (player == null)
                {
                    continue;
                }

                PosedMesh mesh = meshFor(player, bare);
                String name = sanitizedName(player);
                if (mesh != null && name != null)
                {
                    meshes.put(name, mesh);
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
        latestMeshes = meshes;
        latestPlayers = players;
        latestMissing = missing;
        boolean detail = config.showHitboxes() || config.showDebugPanel() || config.debugLogging();
        List<RflEvent> events = tracker.update(meshes, now, client.getTickCount(), detail);
        endMeshFrame(now);
        for (RflEvent event : events)
        {
            PosedMesh.Hits hits = "contact_start".equals(event.type) ? tracker.hits(event.a, event.b) : null;
            if (hits != null)
            {
                double[] c = hits.centroid();
                highlights.add((int) Math.round(c[0]), (int) Math.round(c[1]), now);
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
     * Closes every currently open pair (an empty mesh map ends every active pair rather than
     * dropping it silently), for leaving the POH, a hop, or a logout while reporting is still
     * enabled. Callers that don't need the resulting {@code contact_end} events (reporting
     * disabled, plugin shutdown) should call {@link #reset()} instead.
     *
     * @param client client used only for its tick count; no player state is read
     * @return the contact_end events for every pair that was open
     */
    List<RflEvent> endAll(Client client)
    {
        latestMeshes = Collections.emptyMap();
        latestPlayers = Collections.emptyMap();
        latestMissing = Collections.emptyList();
        return tracker.update(Collections.emptyMap(), System.currentTimeMillis(), client.getTickCount(), false);
    }

    void reset()
    {
        tracker.reset();
        highlights.clear();
        bareBody.reset();
        latestMeshes = Collections.emptyMap();
        latestPlayers = Collections.emptyMap();
        latestMissing = Collections.emptyList();
    }

    /** Players from the latest frame, by sanitized name; read on the client thread only. */
    Map<String, Player> players()
    {
        return latestPlayers;
    }

    /** Meshes from the latest frame, by sanitized name. */
    Map<String, PosedMesh> meshes()
    {
        return latestMeshes;
    }

    List<ContactTracker.Overlap> overlaps()
    {
        return tracker.overlaps();
    }

    /** Average ms per frame spent on mesh work over the last ~10 s window. */
    double meshMsPerFrame()
    {
        return meshMsPerFrame;
    }

    private void endMeshFrame(long now)
    {
        meshNanos += tracker.takeMeshNanos();
        meshFrames++;
        if (now - meshLogAt < LOG_INTERVAL_MS)
        {
            return;
        }
        meshMsPerFrame = meshNanos / 1e6 / meshFrames;
        if (config.debugLogging() && meshLogAt != 0)
        {
            log.info("[RFL debug] mesh contacts: {} ms/frame avg over {} frames ({} meshes)",
                String.format("%.3f", meshMsPerFrame), meshFrames, latestMeshes.size());
        }
        meshNanos = 0;
        meshFrames = 0;
        meshLogAt = now;
    }

    Map<String, List<String>> collidingNow()
    {
        return tracker.collidingNow();
    }

    /** Names of players in view whose mesh could not be built in the latest frame. */
    List<String> missingMeshes()
    {
        return latestMissing;
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

    /** Mesh of the player's drawn model, or their bare body per the Hitbox source setting. */
    private PosedMesh meshFor(Player player, boolean bare)
    {
        Model model = bare ? bareBody.posed(player) : player.getModel();
        LocalPoint localPoint = player.getLocalLocation();
        if (model == null || localPoint == null || model.getFaceIndices1() == null
            || model.getFaceIndices2() == null || model.getFaceIndices3() == null)
        {
            return null;
        }
        long start = System.nanoTime();
        PosedMesh mesh = PosedMesh.from(model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(),
            model.getVerticesCount(), model.getFaceIndices1(), model.getFaceIndices2(), model.getFaceIndices3(),
            model.getFaceCount(), model.getFaceTransparencies(), model.getFaceColors3(),
            player.getCurrentOrientation(), localPoint.getX(), localPoint.getY());
        meshNanos += System.nanoTime() - start;
        return mesh;
    }

    private static String sanitizedName(Player player)
    {
        String name = player.getName();
        return name == null ? null : Text.sanitize(name);
    }
}
