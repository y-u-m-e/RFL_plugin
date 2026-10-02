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
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.kit.KitType;
import net.runelite.client.util.Text;

/**
 * Turns the live players in view into {@link PosedMesh} triangles for {@link ContactTracker} every
 * client frame, from each player's posed model: the drawn model, or the bare body
 * ({@link BareBody}) per the Hitbox source setting. Keeps the latest frame's meshes and pairs for
 * the overlays and the debug panel, adds the contact tile highlight, and measures mesh cost.
 *
 * <p>Contact events are self only (pairs including the local player) and need a handegg held by
 * either body. Pairs of two other players are checked only while one of them holds a handegg (for
 * the name-free collision_seen witness) or while Show hitboxes or Show touching triangles is on.
 *
 * <p>Observer mode: no contact events (open self contacts end); every pair with a handegg holder
 * is tracked locally instead and each finished collision goes to {@link ObserverLog}, never to
 * the event queue.
 *
 * <p>Client thread only: written from {@code ClientTick}, read by overlays (which render on the
 * client thread) and game-tick handlers. The latest-frame maps are replaced, never mutated.
 */
@Slf4j
@Singleton
final class ContactDetector
{
    private static final long LOG_INTERVAL_MS = 10_000;

    private final Client client;
    private final ContactTracker tracker = new ContactTracker(this::tile, this::world);
    private final ContactHighlights highlights;
    private final RflConfig config;
    private final BareBody bareBody;
    private final ObserverLog observerLog;
    private Map<String, PosedMesh> latestMeshes = Collections.emptyMap();
    private Map<String, Player> latestPlayers = Collections.emptyMap();
    private List<String> latestMissing = Collections.emptyList();

    // Mesh cost (building the posed triangles plus checking them), averaged every ~10 s.
    // meshMsPerFrame is volatile only so a stale read is harmless; everything else is client thread.
    private long meshNanos;
    private int meshFrames;
    private long meshLogAt;
    private volatile double meshMsPerFrame;

    @Inject
    ContactDetector(Client client, ContactHighlights highlights, RflConfig config, BareBody bareBody,
        ObserverLog observerLog)
    {
        this.observerLog = observerLog;
        this.client = client;
        this.highlights = highlights;
        this.config = config;
        this.bareBody = bareBody;
    }

    /**
     * Builds this frame's meshes and runs the tracker over them.
     *
     * @return contact events this frame
     */
    List<RflEvent> onFrame(Client client)
    {
        WorldView worldView = client.getTopLevelWorldView();
        Map<String, PosedMesh> meshes = new HashMap<>();
        Map<String, Player> players = new HashMap<>();
        List<String> missing = new ArrayList<>();
        Set<String> holders = new HashSet<>();
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
                    PlayerComposition composition = player.getPlayerComposition();
                    if (composition != null
                        && InterceptionDetector.HANDEGG_ITEMS.contains(composition.getEquipmentId(KitType.WEAPON)))
                    {
                        holders.add(name);
                    }
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
        // Drawing touching triangles needs every pair (other players too) fully counted each frame.
        boolean display = config.showHitboxes() || config.showTouchingTriangles();
        boolean detail = display || config.showDebugPanel() || config.debugLogging();
        Player local = client.getLocalPlayer();
        String self = local == null ? null : sanitizedName(local);
        List<RflEvent> events = tracker.update(meshes, self, holders, display, now,
            client.getTickCount(), detail, config.observerMode());
        saveObserved();
        endMeshFrame(now);
        for (RflEvent event : events)
        {
            PosedMesh.Hits hits = "contact_start".equals(event.type) ? tracker.hits(event.contactId) : null;
            if (hits != null)
            {
                double[] c = hits.centroid();
                highlights.add((int) Math.round(c[0]), (int) Math.round(c[1]), now);
            }
        }
        return events;
    }

    /**
     * World tile {x, y, plane} under a scene point. fromLocalInstance maps instance chunks to their
     * template, so two players in the same house report the same coordinates.
     */
    private int[] tile(double sceneX, double sceneY)
    {
        WorldView worldView = client.getTopLevelWorldView();
        int plane = worldView.getPlane();
        WorldPoint p = WorldPoint.fromLocalInstance(client,
            new LocalPoint((int) Math.round(sceneX), (int) Math.round(sceneY), worldView), plane);
        return new int[]{p.getX(), p.getY(), p.getPlane()};
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
        List<RflEvent> events = tracker.update(Collections.emptyMap(), null, Collections.emptySet(), false,
            System.currentTimeMillis(), client.getTickCount(), false);
        saveObserved();
        return events;
    }

    /**
     * Drops every open pair without events, plus highlights and the bare-body model cache. Open
     * observer collisions are saved as ended first. Client thread.
     */
    void reset()
    {
        tracker.flushObserved(System.currentTimeMillis(), client.getTickCount());
        saveObserved();
        tracker.reset();
        highlights.clear();
        bareBody.reset();
        latestMeshes = Collections.emptyMap();
        latestPlayers = Collections.emptyMap();
        latestMissing = Collections.emptyList();
    }

    private void saveObserved()
    {
        for (ObservedCollision c : tracker.takeObserved())
        {
            observerLog.record(c);
        }
    }

    private int world()
    {
        return client.getWorld();
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

    /** Pairs from the latest frame whose mesh bounds overlap, touching or not. */
    /** Observer collisions in progress, as "A ↔ B". Client thread. */
    List<String> observing()
    {
        return tracker.observing();
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

    /** Name to the names whose triangles touched theirs in the latest frame. */
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
