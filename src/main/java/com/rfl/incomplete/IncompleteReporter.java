package com.rfl.incomplete;

import com.rfl.Handegg;
import com.rfl.RflConfig;
import com.rfl.contact.ContactDetector;
import com.rfl.contact.ContactHighlights;
import com.rfl.log.CollisionLog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.ColorUtil;

/**
 * Feeds the incomplete rule ({@link IncompleteDetector}) from the client and acts on its calls:
 * weapon slots each game tick, the projectiles each frame, and for each incomplete a chat message,
 * a tile highlight and a saved line, per the settings.
 *
 * <p>Threads: client thread only.
 */
@Singleton
public final class IncompleteReporter
{
    /** Saved for a tile that isn't known (the receiver wasn't in view at the catch). */
    private static final int UNKNOWN = -1;

    private final Client client;
    private final RflConfig config;
    private final ContactDetector contactDetector;
    private final ContactHighlights highlights;
    private final CollisionLog collisionLog;
    private final IncompleteDetector detector = new IncompleteDetector();

    @Inject
    IncompleteReporter(Client client, RflConfig config, ContactDetector contactDetector, ContactHighlights highlights,
        CollisionLog collisionLog)
    {
        this.client = client;
        this.config = config;
        this.contactDetector = contactDetector;
        this.highlights = highlights;
        this.collisionLog = collisionLog;
    }

    /**
     * GameTick: who holds a handegg now, and since when. A catch still waiting for its receiver's
     * weapon packet is ruled here, against its catch-time contacts. Forgets everything while
     * detection or Detect incompletes is off.
     */
    public void onGameTick(boolean detecting)
    {
        if (!detecting || !config.detectIncompletes())
        {
            detector.reset();
            return;
        }
        report(detector.onHolders(client.getGameCycle(), IncompleteDetector.holders(contactDetector.weapons(client))));
    }

    /**
     * ClientTick, after the contact update: the incomplete is decided on the client cycle the
     * handegg projectile disappears (the catch), against open collisions and touches within
     * {@link IncompleteDetector#CONTACT_GRACE_CYCLES} before it. Every other frame costs one pass
     * over the drawn projectiles.
     */
    public void onFrame()
    {
        if (!config.detectIncompletes())
        {
            return;
        }
        boolean ballInFlight = Handegg.anyInFlight(client.getProjectiles());
        int cycle = client.getGameCycle();
        report(detector.onFrame(cycle, System.currentTimeMillis(), client.getTickCount(), ballInFlight,
            () -> contactDetector.contactsAt(cycle, IncompleteDetector.CONTACT_GRACE_CYCLES), this::positionsNow));
    }

    /** Forgets every holder and pending catch (shut-down). */
    public void reset()
    {
        detector.reset();
    }

    /** Every player in view's local {x, y}, by sanitized name. */
    private Map<String, int[]> positionsNow()
    {
        Map<String, int[]> positions = new HashMap<>();
        for (Map.Entry<String, Player> entry : contactDetector.players().entrySet())
        {
            LocalPoint at = entry.getValue().getLocalLocation();
            if (at != null)
            {
                positions.put(entry.getKey(), new int[] { at.getX(), at.getY() });
            }
        }
        return positions;
    }

    private void report(List<IncompleteDetector.Call> found)
    {
        for (IncompleteDetector.Call incomplete : found)
        {
            show(incomplete);
            save(incomplete);
        }
    }

    /** Chat message and receiver tile highlight for one incomplete, per the settings. */
    private void show(IncompleteDetector.Call incomplete)
    {
        if (config.incompleteChatMessage())
        {
            String label = ColorUtil.wrapWithColorTag("Incomplete:", config.incompleteColor());
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                label + " " + incomplete.receiver + " caught the handegg in contact with "
                    + String.join(", ", incomplete.contacts), null);
        }
        int[] at = incomplete.receiverAt;
        if (config.highlightIncompletes() && at != null)
        {
            highlights.addIncomplete(at[0], at[1], System.currentTimeMillis());
        }
    }

    /**
     * Appends the incomplete to the day file (when Save collisions is on), with the receiver's tile
     * at the catch and the exact catch time and cycle.
     */
    private void save(IncompleteDetector.Call incomplete)
    {
        WorldView view = client.getTopLevelWorldView();
        LocalPoint at = incomplete.receiverAt == null || view == null ? null
            : new LocalPoint(incomplete.receiverAt[0], incomplete.receiverAt[1], view);
        WorldPoint tile = at == null ? null : WorldPoint.fromLocalInstance(client, at, view.getPlane());
        collisionLog.record(new Incomplete(incomplete.receiver, incomplete.contacts,
            incomplete.catchMs, incomplete.catchTick, client.getWorld(),
            tile == null ? UNKNOWN : tile.getX(), tile == null ? UNKNOWN : tile.getY(),
            tile == null ? UNKNOWN : tile.getPlane(),
            at == null ? UNKNOWN : at.getSceneX(), at == null ? UNKNOWN : at.getSceneY(), incomplete.catchCycle));
    }
}
