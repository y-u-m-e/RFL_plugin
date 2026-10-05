package com.rfl.replay;

import sh.yumekui.toolkit.text.PlayerNames;
import com.rfl.Handegg;
import sh.yumekui.toolkit.model.ModelCapture;
import sh.yumekui.toolkit.model.RenderableModels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.IterableHashTable;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Projectile;
import net.runelite.api.Renderable;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * Reads what the replay records from the client, as plain values for {@link ReplaySampler}: each
 * player's pose and spot anims every frame, each thrown handegg every frame, and appearances and
 * true tiles every game tick. Models are not read here: each player and ball carries a capture the
 * sampler calls only for a model key it hasn't seen.
 *
 * <p>Client thread only.
 */
final class FrameReader
{
    private final RecorderStats stats;

    FrameReader(RecorderStats stats)
    {
        this.stats = stats;
    }

    /** Every named player in view with a position, this frame. */
    List<PlayerState> players(WorldView view)
    {
        List<PlayerState> players = new ArrayList<>();
        for (Player player : view.players())
        {
            String name = PlayerNames.sanitized(player);
            LocalPoint at = name == null ? null : player.getLocalLocation();
            if (at == null)
            {
                continue;
            }
            // Player#getModel() builds the posed model: the sampler calls this only on a new key.
            players.add(new PlayerState(name, at.getX(), at.getY(), player.getCurrentOrientation(),
                player.getAnimation(), player.getAnimationFrame(), player.getPoseAnimation(),
                player.getPoseAnimationFrame(), spots(player), () -> capture(player), look(player)));
        }
        return players;
    }

    /** Every thrown handegg drawn this frame. */
    List<Ball> balls(Client client)
    {
        List<Ball> balls = new ArrayList<>();
        for (Projectile projectile : client.getProjectiles())
        {
            if (Handegg.isThrown(projectile))
            {
                balls.add(new Ball(projectile.getId(), projectile.getStartCycle(), projectile.getX(),
                    projectile.getY(), projectile.getZ(), projectile.getOrientation(), () -> capture(projectile)));
            }
        }
        return balls;
    }

    /** Fills each named player's appearance and true tile, for a game tick. */
    void tickState(WorldView view, List<Appearance> appearances, List<TrueTile> trueTiles)
    {
        for (Player player : view.players())
        {
            String name = PlayerNames.sanitized(player);
            if (name == null)
            {
                continue;
            }
            PlayerComposition composition = player.getPlayerComposition();
            if (composition != null)
            {
                // Copies: the client mutates these arrays in place, and an app line may be serialised later.
                appearances.add(new Appearance(name, composition.getGender(), copy(composition.getEquipmentIds()),
                    copy(composition.getColors())));
            }
            // The true tile's centre in local units, so it compares directly with the f rows' x/y.
            WorldPoint world = player.getWorldLocation();
            LocalPoint tile = world == null ? null : LocalPoint.fromWorld(view, world);
            if (tile != null)
            {
                trueTiles.add(new TrueTile(name, tile.getX(), tile.getY()));
            }
        }
    }

    /**
     * A renderable's current model ({@link Renderable#getModel()}: a player or projectile builds its
     * current frame) copied into replay geometry. Null when there is no model or its arrays are
     * missing; a read that throws is counted and treated the same, so one odd model never breaks a
     * recording.
     */
    private ModelCapture.Geometry capture(Renderable renderable)
    {
        try
        {
            Model model = renderable.getModel();
            return model == null || !RenderableModels.hasArrays(model) ? null : RenderableModels.geometry(model);
        }
        catch (RuntimeException e)
        {
            stats.captureFailed();
            return null;
        }
    }

    /**
     * A player's spot anims as flat {@code (id, frame, height)} triples, or the shared
     * {@link PlayerState#NO_SPOTS} when there are none, so the common case allocates no array.
     */
    private static int[] spots(Player player)
    {
        IterableHashTable<ActorSpotAnim> table = player.getSpotAnims();
        if (table == null)
        {
            return PlayerState.NO_SPOTS;
        }
        int count = 0;
        for (ActorSpotAnim ignored : table)
        {
            count++;
        }
        if (count == 0)
        {
            return PlayerState.NO_SPOTS;
        }
        int[] flat = new int[count * 3];
        int at = 0;
        for (ActorSpotAnim spot : table)
        {
            // The table may have grown since it was counted; the extra entries wait for the next frame.
            if (at >= flat.length)
            {
                break;
            }
            flat[at++] = spot.getId();
            flat[at++] = spot.getFrame();
            flat[at++] = spot.getHeight();
        }
        return at == flat.length ? flat : Arrays.copyOf(flat, at);
    }

    /**
     * The player's appearance hash as of this frame, or null without a composition. Hashes the
     * composition's own equipment and colour arrays in place (17 ints, no copy), which is cheaper than
     * any other correct change signal: the arrays can be mutated in place, so an identity check on the
     * composition would miss changes.
     */
    private static Integer look(Player player)
    {
        PlayerComposition composition = player.getPlayerComposition();
        return composition == null ? null
            : Appearance.hash(composition.getGender(), composition.getEquipmentIds(), composition.getColors());
    }

    private static int[] copy(int[] values)
    {
        return values == null ? null : values.clone();
    }

}
