package com.rfl.contact;

import sh.yumekui.toolkit.text.PlayerNames;
import com.rfl.Handegg;
import com.rfl.RflConfig;
import com.rfl.log.CollisionLog;
import com.rfl.teams.Teams;
import sh.yumekui.toolkit.geom.TriangleMesh;

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
import net.runelite.api.Projectile;
import net.runelite.api.Actor;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.kit.KitType;

/**
 * Turns the live players in view into {@link TriangleMesh} triangles for {@link ContactTracker} every
 * client frame, from each player's posed model: the drawn model, or the bare body
 * ({@link BareBody}) per the Hitbox source setting. Keeps the latest frame's meshes and pairs for
 * the overlays, adds the contact tile highlight when a collision starts, and hands each finished
 * collision to {@link CollisionLog}.
 *
 * <p>Only a pair on opposite teams (Team A against Team B, {@link Teams#opposing}) is ever checked;
 * same-team pairs and any pair with an unassigned player never are, so with nobody assigned
 * nothing is detected, saved, recorded or drawn as touching.
 *
 * <p>A pair is triangle-checked only when a handegg is held by either body, the local player's own
 * pairs included; a pair with no holder is never triangle-checked, on display or off. Show
 * hitboxes and Show touching triangles only ask for the full touching count on pairs already
 * checked, so the overlay fill stays complete without widening which pairs get checked.
 *
 * <p>Client thread only: written from {@code ClientTick}, read by overlays (which render on the
 * client thread) and game-tick handlers. The latest-frame maps are replaced, never mutated.
 */
@Singleton
public final class ContactDetector
{
    /** Within this many local units (2 tiles) of a projectile's end point counts as its possible receiver. */
    public static final int RECEIVER_RADIUS = 256;
    /** The end-point fallback only applies over the last this many cycles of a flight. */
    static final int RECEIVER_FALLBACK_CYCLES = 10;

    private final Client client;
    private final ContactTracker tracker = new ContactTracker(this::tile, this::world);
    private final ContactHighlights highlights;
    private final RflConfig config;
    private final BareBody bareBody;
    private final CollisionLog collisionLog;
    private final Teams teams;
    private Map<String, TriangleMesh> latestMeshes = Collections.emptyMap();
    private Map<String, Player> latestPlayers = Collections.emptyMap();
    /** Whether detection ran since the last reset, so stopping saves and clears only once. */
    private boolean tracking;

    @Inject
    ContactDetector(Client client, ContactHighlights highlights, RflConfig config, BareBody bareBody,
        CollisionLog collisionLog, Teams teams)
    {
        this.teams = teams;
        this.collisionLog = collisionLog;
        this.client = client;
        this.highlights = highlights;
        this.config = config;
        this.bareBody = bareBody;
    }

    /**
     * Runs the tracker over this frame and saves finished collisions. Meshes are built for every
     * player on Team A or Team B ({@link #onATeam}), and for everyone while Show hitboxes draws every
     * player's wireframe.
     */
    public void onFrame(Client client)
    {
        tracking = true;
        WorldView worldView = client.getTopLevelWorldView();
        Map<String, Player> players = new HashMap<>();
        Map<String, Teams.Team> teamOf = new HashMap<>();
        Set<String> holders = new HashSet<>();
        if (worldView != null)
        {
            for (Player player : worldView.players())
            {
                String name = PlayerNames.sanitized(player);
                if (name == null)
                {
                    continue;
                }
                players.put(name, player);
                // The team gate: looked up once per player per frame, so each pair check is two map reads.
                teamOf.put(name, teams.team(name));
                PlayerComposition composition = player.getPlayerComposition();
                if (composition != null && Handegg.isHandegg(composition.getEquipmentId(KitType.WEAPON)))
                {
                    holders.add(name);
                }
            }
        }
        Set<String> receivers = flightReceivers(client, players);
        boolean wireframe = config.showHitboxes();
        // Owner ruling: every Team A and Team B player gets a mesh every frame, holder or not. The
        // incomplete rule needs the receiver's contacts during the flight and the grace window
        // before the catch, before anyone is known to hold the egg, so do not "optimise" this to
        // holders only. Unassigned players are skipped: the team gate never lets them count.
        Set<String> needMeshes = wireframe ? players.keySet() : onATeam(teamOf);
        boolean bare = config.hitboxSource() == RflConfig.HitboxSource.BARE_BODY;
        Map<String, TriangleMesh> meshes = new HashMap<>();
        for (String name : needMeshes)
        {
            TriangleMesh mesh = meshFor(players.get(name), bare);
            if (mesh != null)
            {
                meshes.put(name, mesh);
            }
        }

        long now = System.currentTimeMillis();
        latestMeshes = meshes;
        latestPlayers = players;
        // Show hitboxes/Show touching triangles need the full touching count every frame, on
        // handegg pairs only; they never widen which pairs get triangle-checked.
        boolean display = wireframe || config.showTouchingTriangles();
        tracker.setPairFilter((a, b) -> Teams.opposing(teamOf.get(a), teamOf.get(b)));
        tracker.setCycle(client.getGameCycle());
        tracker.setFlightReceivers(receivers);
        List<TriangleMesh.Hits> started = tracker.update(meshes, holders, now, client.getTickCount(), display);
        saveFinished();
        for (TriangleMesh.Hits hits : started)
        {
            double[] centre = hits.centroid();
            highlights.add((int) Math.round(centre[0]), (int) Math.round(centre[1]), now);
        }
    }

    /** Names of the players on Team A or Team B: the only ones the team gate can let count. */
    private static Set<String> onATeam(Map<String, Teams.Team> teamOf)
    {
        Set<String> assigned = new HashSet<>();
        for (Map.Entry<String, Teams.Team> entry : teamOf.entrySet())
        {
            if (entry.getValue() != null)
            {
                assigned.add(entry.getKey());
            }
        }
        return assigned;
    }

    /**
     * Each in-flight handegg's intended receiver, so their contact during the flight is tracked
     * before their weapon slot shows the egg: the projectile's target actor when the client sets
     * one, else, over the last {@link #RECEIVER_FALLBACK_CYCLES} of the flight, the players within
     * {@link #RECEIVER_RADIUS} of its end point. Empty (and no extra work) with nothing in flight.
     */
    private static Set<String> flightReceivers(Client client, Map<String, Player> players)
    {
        Set<String> out = null;
        int cycle = client.getGameCycle();
        for (Projectile projectile : client.getProjectiles())
        {
            if (!Handegg.isThrown(projectile))
            {
                continue;
            }
            if (out == null)
            {
                out = new HashSet<>();
            }
            Actor target = projectile.getTargetActor();
            if (target instanceof Player)
            {
                String name = PlayerNames.sanitized((Player) target);
                if (name != null)
                {
                    out.add(name);
                    continue;
                }
            }
            WorldPoint endPoint = projectile.getEndCycle() - cycle > RECEIVER_FALLBACK_CYCLES ? null
                : projectile.getTargetPoint();
            LocalPoint end = endPoint == null ? null : LocalPoint.fromWorld(client, endPoint);
            if (end == null)
            {
                continue;
            }
            Map<String, int[]> at = new HashMap<>();
            for (Map.Entry<String, Player> player : players.entrySet())
            {
                LocalPoint position = player.getValue().getLocalLocation();
                if (position != null)
                {
                    at.put(player.getKey(), new int[] { position.getX(), position.getY() });
                }
            }
            out.addAll(near(at, end.getX(), end.getY(), RECEIVER_RADIUS));
        }
        return out == null ? Collections.emptySet() : out;
    }

    /** Names whose local position is within {@code radius} (Chebyshev) of x, y. */
    public static Set<String> near(Map<String, int[]> positions, int x, int y, int radius)
    {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, int[]> entry : positions.entrySet())
        {
            int[] position = entry.getValue();
            if (Math.max(Math.abs(position[0] - x), Math.abs(position[1] - y)) <= radius)
            {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    /**
     * World tile {x, y, plane} under a local point, plus that point's scene tile {sceneX, sceneY}.
     * fromLocalInstance maps instance chunks to their template, so two players in the same house get
     * the same world x/y/plane; the scene tile stays unique within the loaded house (0..103).
     */
    private TileRef tile(double localX, double localY)
    {
        WorldView worldView = client.getTopLevelWorldView();
        int plane = worldView.getPlane();
        LocalPoint localPoint = new LocalPoint((int) Math.round(localX), (int) Math.round(localY), worldView);
        WorldPoint world = WorldPoint.fromLocalInstance(client, localPoint, plane);
        return new TileRef(world.getX(), world.getY(), world.getPlane(), localPoint.getSceneX(), localPoint.getSceneY());
    }

    /**
     * Saves every open collision as ended now and drops all tracking state, highlights and the
     * bare-body model cache. For leaving the POH, a hop, a logout, Detect contacts going off, or
     * shutdown. Client thread.
     */
    public void reset()
    {
        tracking = false;
        tracker.flush(System.currentTimeMillis(), client.getTickCount());
        saveFinished();
        tracker.reset();
        highlights.clear();
        bareBody.reset();
        latestMeshes = Collections.emptyMap();
        latestPlayers = Collections.emptyMap();
    }

    /**
     * Client thread. Once per stop (left the house, logged out, hopped, Detect contacts off): saves
     * open collisions as ended and clears the tracker. A no-op while already stopped.
     */
    public void stopTracking()
    {
        if (tracking)
        {
            reset();
        }
    }

    /** Contact detection runs logged in, inside a POH, with Detect contacts on. */
    public static boolean detects(boolean detectContacts, boolean loggedIn, boolean inPoh)
    {
        return detectContacts && loggedIn && inPoh;
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
    public Map<String, Player> players()
    {
        return latestPlayers;
    }

    /** Meshes from the latest frame, by sanitized name. */
    public Map<String, TriangleMesh> meshes()
    {
        return latestMeshes;
    }

    /** Pairs from the latest frame whose mesh bounds overlap, touching or not. */
    public List<ContactTracker.Overlap> overlaps()
    {
        return tracker.overlaps();
    }

    /** {@link ContactTracker#contactsAt}: the incomplete rule's contacts at the latest frame's cycle. */
    public Map<String, List<String>> contactsAt(int cycle, int grace)
    {
        return tracker.contactsAt(cycle, grace);
    }

    /** Weapon-slot item id of every player in view, by sanitized name (-1 for empty). */
    public Map<String, Integer> weapons(Client client)
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
            String name = PlayerNames.sanitized(player);
            if (composition != null && name != null)
            {
                weapons.put(name, composition.getEquipmentId(KitType.WEAPON));
            }
        }
        return weapons;
    }

    /** Mesh of the player's drawn model, or their bare body per the Hitbox source setting. */
    private TriangleMesh meshFor(Player player, boolean bare)
    {
        Model model = bare ? bareBody.posed(player) : player.getModel();
        LocalPoint localPoint = player.getLocalLocation();
        if (model == null || localPoint == null || model.getFaceIndices1() == null
            || model.getFaceIndices2() == null || model.getFaceIndices3() == null)
        {
            return null;
        }
        return TriangleMesh.from(model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(),
            model.getVerticesCount(), model.getFaceIndices1(), model.getFaceIndices2(), model.getFaceIndices3(),
            model.getFaceCount(), model.getFaceTransparencies(), model.getFaceColors3(),
            player.getCurrentOrientation(), localPoint.getX(), localPoint.getY());
    }

}
