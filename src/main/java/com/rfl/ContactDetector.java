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
 * the overlays and the debug panel, adds the contact tile highlight when a collision starts, hands
 * each finished collision to {@link CollisionLog}, and measures mesh cost.
 *
 * <p>Every pair in view is tracked alike, the local player's included; a collision needs a
 * handegg held by either body. Pairs without a holder are checked only while Show hitboxes or
 * Show touching triangles is on.
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
    private final CollisionLog collisionLog;
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
        CollisionLog collisionLog)
    {
        this.collisionLog = collisionLog;
        this.client = client;
        this.highlights = highlights;
        this.config = config;
        this.bareBody = bareBody;
    }

    /** Builds this frame's meshes, runs the tracker over them and saves finished collisions. */
    void onFrame(Client client)
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
        List<PosedMesh.Hits> started = tracker.update(meshes, holders, display, now, client.getTickCount(), detail);
        saveFinished();
        endMeshFrame(now);
        for (PosedMesh.Hits hits : started)
        {
            double[] c = hits.centroid();
            highlights.add((int) Math.round(c[0]), (int) Math.round(c[1]), now);
        }
    }

    /**
     * World tile {x, y, plane} under a scene point. fromLocalInstance maps instance chunks to their
     * template, so two players in the same house get the same coordinates.
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
     * Saves every open collision as ended now and drops all tracking state, highlights and the
     * bare-body model cache. For leaving the POH, a hop, a logout, Detect contacts going off, or
     * shutdown. Client thread.
     */
    void reset()
    {
        tracker.flush(System.currentTimeMillis(), client.getTickCount());
        saveFinished();
        tracker.reset();
        highlights.clear();
        bareBody.reset();
        latestMeshes = Collections.emptyMap();
        latestPlayers = Collections.emptyMap();
        latestMissing = Collections.emptyList();
    }

    private void saveFinished()
    {
        for (Collision c : tracker.takeFinished())
        {
            collisionLog.record(c);
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

    /** Collisions in progress, as "A ↔ B". Client thread. */
    List<String> inProgress()
    {
        return tracker.inProgress();
    }

    /** Pairs from the latest frame whose mesh bounds overlap, touching or not. */
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
