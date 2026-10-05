package com.rfl.replay;

import com.rfl.replay.pitch.LocPass;
import sh.yumekui.toolkit.model.ModelCapture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Turns per-cycle player, ball and appearance state into the replay lines the writer appends to
 * disk (spec §2.2, model lines §2.3), writing only what changed. Pure Java: no client and no IO,
 * so it is unit tested without a client.
 *
 * <p>The caller passes already-sanitized names. State kept across calls: a stable name-to-index
 * assignment (first-seen order, never reused or shrunk), each player's last <em>written</em>
 * {@code f} row, the names present on the previous {@link #frame}, the names {@link #frame} has
 * spawned, and each player's last appearance hash.
 *
 * <p>A despawn forgets the player's last written row, true tile, spot anims and model, so a
 * respawn always writes them afresh even if they match what was last sent. {@link #tick} only
 * writes {@code app} and {@code tt} for spawned players, never assigning an index to anyone else,
 * and a spawn clears the stored appearance hash so the next tick writes a fresh {@code app}. A
 * player with no stored spot anims counts as having none, so a spawn with no graphics writes no
 * {@code spot} line.
 *
 * <p>Client thread only.
 */
public final class ReplaySampler
{
    /** ClientTicks (about 1 s) to wait for a spot anim to end before capturing with it merged. */
    static final int SPOT_DEFER_CAP = 50;
    /** Model key prefixes: a player pose and a thrown ball. */
    private static final String PLAYER_KEY = "p:";
    private static final String BALL_KEY = "b:";
    /** Marks a player key captured with spot anims merged in, followed by the spot ids. */
    private static final String SPOT_KEY = ":s";
    /** Values per spot anim in a flat {@code (id, frame, height)} array. */
    private static final int SPOT_FIELDS = 3;

    private final Map<String, Integer> indices = new LinkedHashMap<>();
    private final Map<String, int[]> lastRow = new LinkedHashMap<>();
    private final Map<String, Integer> lastAppearanceHash = new LinkedHashMap<>();
    /** Last written true tile per name, as {@code [x, y]}. */
    private final Map<String, int[]> lastTrueTile = new LinkedHashMap<>();
    /** Last written spot anims per name, sorted flat triples; absent means none. */
    private final Map<String, int[]> lastSpots = new LinkedHashMap<>();
    private Set<String> present = new LinkedHashSet<>();
    /** Names {@link #frame} has currently spawned; only its own spawn emission adds to this. */
    private final Set<String> spawned = new LinkedHashSet<>();

    private final ModelLines models = new ModelLines();
    /**
     * Latest appearance hash per name from {@link #tick}, for player model keys. Unlike
     * {@link #lastAppearanceHash} it is not cleared on spawn or despawn, so a returning player's
     * model key is ready at once; every tick refreshes it.
     */
    private final Map<String, Integer> modelAppearance = new LinkedHashMap<>();
    /** Last model id written in a {@code pm} row per name; cleared on despawn. */
    private final Map<String, Integer> lastModel = new LinkedHashMap<>();
    /**
     * ClientTicks a player's capture has waited on an active spot anim, with no clean model for the
     * pose. Cleared when the spot anims clear and on despawn.
     */
    private final Map<String, Integer> spotDeferred = new LinkedHashMap<>();
    private long lastModelNanos;
    private boolean lastFrameNewModels;

    /** Distinct model keys captured so far. */
    int modelsCaptured()
    {
        return models.size();
    }

    /** Nanoseconds the last {@link #frame} spent resolving model keys (capture included). */
    long lastModelNanos()
    {
        return lastModelNanos;
    }

    /** Whether the last {@link #frame} captured at least one new model. */
    boolean lastFrameNewModels()
    {
        return lastFrameNewModels;
    }

    /** {@code model} lines for every id captured since the last call (by any path), in id order. */
    public List<Map<String, Object>> newModelLines()
    {
        return models.takeLines();
    }

    /** The id for a house object's model key, capturing it when new ({@link LocPass}); -1 when none. */
    public int locModelId(String key, Supplier<ModelCapture.Geometry> capture)
    {
        return models.idFor(key, ModelLines.LOC, capture, 0);
    }

    /**
     * Lines for one ClientTick: model lines for keys first seen this cycle, then despawn, spawn,
     * f, pm, spot, then ball lines, in that order. Model lines come first so each precedes the
     * {@code pm} / {@code ball} lines that refer to its id.
     */
    public List<Map<String, Object>> frame(int cycle, List<PlayerState> players, List<Ball> balls)
    {
        List<Map<String, Object>> lines = new ArrayList<>();

        long modelStart = System.nanoTime();
        int modelsBefore = models.size();
        int[] playerModels = playerModelIds(players);
        int[] ballModels = ballModelIds(cycle, balls);
        lastFrameNewModels = models.size() != modelsBefore;
        if (lastFrameNewModels)
        {
            lines.addAll(models.takeLines());
        }
        lastModelNanos = System.nanoTime() - modelStart;

        Set<String> now = new LinkedHashSet<>();
        for (PlayerState player : players)
        {
            now.add(player.name);
        }
        addDespawns(cycle, now, lines);
        addSpawns(cycle, players, lines);
        addPoseRows(cycle, players, lines);
        addModelRows(cycle, players, playerModels, lines);
        addSpotLines(cycle, players, lines);
        addBallLines(cycle, balls, ballModels, lines);
        present = now;
        return lines;
    }

    private int[] playerModelIds(List<PlayerState> players)
    {
        int[] ids = new int[players.size()];
        for (int k = 0; k < ids.length; k++)
        {
            ids[k] = playerModelId(players.get(k));
        }
        return ids;
    }

    /** A ball still parked before its start cycle gets no model (-1), like it gets no line. */
    private int[] ballModelIds(int cycle, List<Ball> balls)
    {
        int[] ids = new int[balls.size()];
        for (int k = 0; k < ids.length; k++)
        {
            Ball ball = balls.get(k);
            ids[k] = cycle <= ball.startCycle ? -1 : models.idFor(BALL_KEY + ball.id, ModelLines.BALL, ball.model, 0);
        }
        return ids;
    }

    /** A {@code despawn} for each name present last frame and gone now, forgetting what was last written for it. */
    private void addDespawns(int cycle, Set<String> now, List<Map<String, Object>> lines)
    {
        for (String name : present)
        {
            if (!now.contains(name))
            {
                lines.add(ReplayLines.despawn(cycle, indexOf(name)));
                lastRow.remove(name);
                // Un-spawned, so tick() writes no app or tt for the name until it respawns.
                spawned.remove(name);
                lastTrueTile.remove(name);
                lastSpots.remove(name);
                lastModel.remove(name);
                spotDeferred.remove(name);
            }
        }
    }

    /** A {@code spawn} for each player not present last frame. */
    private void addSpawns(int cycle, List<PlayerState> players, List<Map<String, Object>> lines)
    {
        for (PlayerState player : players)
        {
            if (!present.contains(player.name))
            {
                lines.add(ReplayLines.spawn(cycle, indexFor(player.name), player.name));
                spawned.add(player.name);
                // So the next tick() writes a fresh app, even if it matches what was last sent.
                lastAppearanceHash.remove(player.name);
            }
        }
    }

    /** One {@code f} line with a row for each player whose row differs from the last one written. */
    private void addPoseRows(int cycle, List<PlayerState> players, List<Map<String, Object>> lines)
    {
        List<int[]> rows = new ArrayList<>();
        for (PlayerState player : players)
        {
            int[] row = player.row(indexFor(player.name));
            int[] last = lastRow.get(player.name);
            if (last == null || !Arrays.equals(last, row))
            {
                rows.add(row);
                lastRow.put(player.name, row);
            }
        }
        if (!rows.isEmpty())
        {
            lines.add(ReplayLines.poses(cycle, rows));
        }
    }

    /** One {@code pm} line with a row for each player whose model id changed. */
    private void addModelRows(int cycle, List<PlayerState> players, int[] modelIds, List<Map<String, Object>> lines)
    {
        List<int[]> rows = new ArrayList<>();
        for (int k = 0; k < modelIds.length; k++)
        {
            int id = modelIds[k];
            if (id < 0)
            {
                continue;
            }
            String name = players.get(k).name;
            Integer last = lastModel.get(name);
            if (last == null || last != id)
            {
                rows.add(new int[] { indexFor(name), id });
                lastModel.put(name, id);
            }
        }
        if (!rows.isEmpty())
        {
            lines.add(ReplayLines.playerModels(cycle, rows));
        }
    }

    /** A {@code spot} line for each player whose spot anims changed (an empty one when they cleared). */
    private void addSpotLines(int cycle, List<PlayerState> players, List<Map<String, Object>> lines)
    {
        for (PlayerState player : players)
        {
            sortSpots(player.spots);
            int[] last = lastSpots.get(player.name);
            boolean changed = last == null ? player.spots.length > 0 : !Arrays.equals(last, player.spots);
            if (!changed)
            {
                continue;
            }
            lines.add(ReplayLines.spots(cycle, indexFor(player.name), player.spots));
            if (player.spots.length == 0)
            {
                lastSpots.remove(player.name);
            }
            else
            {
                lastSpots.put(player.name, player.spots);
            }
        }
    }

    /** A {@code ball} line for each projectile past its start cycle. */
    private void addBallLines(int cycle, List<Ball> balls, int[] modelIds, List<Map<String, Object>> lines)
    {
        for (int k = 0; k < modelIds.length; k++)
        {
            Ball ball = balls.get(k);
            // The client creates a projectile ~0.8 s early, parked at (0, 0) through its start
            // cycle; it has a real position only from the cycle after.
            if (cycle > ball.startCycle)
            {
                lines.add(ReplayLines.ball(cycle, ball, modelIds[k]));
            }
        }
    }

    /**
     * Lines for one GameTick: tick, then app lines for spawned players whose appearance changed,
     * then one tt line for spawned players whose true tile changed (none when nothing did). An
     * appearance or true tile for a name {@link #frame} hasn't spawned (yet, or anymore) is skipped
     * and never assigned an index.
     */
    List<Map<String, Object>> tick(int cycle, int tick, List<Appearance> appearances, List<TrueTile> trueTiles)
    {
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(ReplayLines.tick(cycle, tick));
        addAppearances(cycle, appearances, lines);
        addTrueTiles(cycle, trueTiles, lines);
        return lines;
    }

    private void addAppearances(int cycle, List<Appearance> appearances, List<Map<String, Object>> lines)
    {
        for (Appearance appearance : appearances)
        {
            int hash = appearance.hash();
            // Kept for every name, spawned or not, so a player's model key is ready by their spawn.
            modelAppearance.put(appearance.name, hash);
            if (!spawned.contains(appearance.name))
            {
                continue;
            }
            Integer lastHash = lastAppearanceHash.get(appearance.name);
            if (lastHash == null || lastHash != hash)
            {
                lines.add(ReplayLines.appearance(cycle, indexOf(appearance.name), appearance));
                lastAppearanceHash.put(appearance.name, hash);
            }
        }
    }

    private void addTrueTiles(int cycle, List<TrueTile> trueTiles, List<Map<String, Object>> lines)
    {
        List<int[]> rows = new ArrayList<>();
        for (TrueTile tile : trueTiles)
        {
            if (!spawned.contains(tile.name))
            {
                continue;
            }
            int[] last = lastTrueTile.get(tile.name);
            if (last == null || last[0] != tile.x || last[1] != tile.y)
            {
                rows.add(new int[] { indexOf(tile.name), tile.x, tile.y });
                lastTrueTile.put(tile.name, new int[] { tile.x, tile.y });
            }
        }
        if (!rows.isEmpty())
        {
            lines.add(ReplayLines.trueTiles(cycle, rows));
        }
    }

    /**
     * Sorts flat {@code (id, frame, height)} triples in place, so the same set compares equal
     * whatever order the client's hash table iterates in. Insertion sort: a player carries a handful
     * of spot anims at most.
     */
    static void sortSpots(int[] flat)
    {
        for (int next = SPOT_FIELDS; next + 2 < flat.length; next += SPOT_FIELDS)
        {
            for (int at = next; at >= SPOT_FIELDS && compareTriples(flat, at - SPOT_FIELDS, at) > 0;
                at -= SPOT_FIELDS)
            {
                swapTriples(flat, at - SPOT_FIELDS, at);
            }
        }
    }

    private static int compareTriples(int[] flat, int first, int second)
    {
        for (int field = 0; field < SPOT_FIELDS; field++)
        {
            int order = Integer.compare(flat[first + field], flat[second + field]);
            if (order != 0)
            {
                return order;
            }
        }
        return 0;
    }

    private static void swapTriples(int[] flat, int first, int second)
    {
        for (int field = 0; field < SPOT_FIELDS; field++)
        {
            int held = flat[first + field];
            flat[first + field] = flat[second + field];
            flat[second + field] = held;
        }
    }

    /** Index assigned to a name, or -1 if never seen. */
    int indexOf(String name)
    {
        Integer index = indices.get(name);
        return index == null ? -1 : index;
    }

    /** Names {@link #frame} has spawned and not despawned since, in spawn order (a copy). */
    List<String> spawnedNames()
    {
        return new ArrayList<>(spawned);
    }

    /** Index of a player {@link #frame} has spawned and not despawned since, or -1. */
    int spawnedIndex(String name)
    {
        return spawned.contains(name) ? indexOf(name) : -1;
    }

    /** Index for a name, assigning the next one in first-seen order if this is new. */
    private int indexFor(String name)
    {
        Integer index = indices.get(name);
        if (index == null)
        {
            index = indices.size();
            indices.put(name, index);
        }
        return index;
    }

    /**
     * The model id for this player's current key, capturing on a new key; -1 when there is no model
     * yet. Waits for the appearance hash from {@link #tick}, and never captures while a spot anim is
     * active: the client merges spot anim models into {@code Player#getModel()}, and the first model
     * per key is kept forever. The player's previous {@code pm} value carries on.
     */
    private int playerModelId(PlayerState player)
    {
        if (player.model == null)
        {
            return -1;
        }
        Integer appearance = modelAppearance.get(player.name);
        if (appearance == null || (player.look != null && player.look.intValue() != appearance))
        {
            // Unknown look, or the composition changed since the last tick hashed it: capturing
            // now would file the new look under the old key. The next tick re-hashes.
            return -1;
        }
        String key = PLAYER_KEY + appearance + ":" + player.anim + ":" + player.animFrame + ":" + player.pose
            + ":" + player.poseFrame;
        if (player.spots.length == 0)
        {
            spotDeferred.remove(player.name);
            return models.idFor(key, ModelLines.PLAYER, player.model, appearance);
        }
        // A spot anim is merged into the client's player model. Use the clean pose if it is known;
        // otherwise wait up to SPOT_DEFER_CAP ClientTicks for the spot anim to end, then capture
        // under a key naming the spot ids, so the merged model never takes the clean key.
        int clean = models.idFor(key, ModelLines.PLAYER, null, appearance);
        if (clean >= 0)
        {
            return clean;
        }
        int deferred = spotDeferred.merge(player.name, 1, Integer::sum);
        if (deferred <= SPOT_DEFER_CAP)
        {
            return -1;
        }
        return models.idFor(spotKey(key, player.spots), ModelLines.PLAYER, player.model, appearance);
    }

    /** {@code key} plus {@link #SPOT_KEY} and the sorted spot ids, comma separated. */
    private static String spotKey(String key, int[] spots)
    {
        sortSpots(spots);
        StringBuilder spotKey = new StringBuilder(key).append(SPOT_KEY);
        for (int at = 0; at + 2 < spots.length; at += SPOT_FIELDS)
        {
            spotKey.append(at == 0 ? "" : ",").append(spots[at]);
        }
        return spotKey.toString();
    }
}
