package com.rfl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpotanimID;

/**
 * Interception rule, evaluated once per game tick: a player who starts holding a handegg right
 * after a thrown handegg stopped being drawn in mid air, while in contact with another player,
 * intercepted it. Hand-to-hand passes without a throw and uncontested catches don't count.
 */
final class InterceptionDetector
{
    /** Holy, Peaceful and Chaotic handegg (Easter 2018). */
    static final Set<Integer> HANDEGG_ITEMS = Set.of(
        ItemID.EASTER18_HANDEGG_LIGHT, ItemID.EASTER18_HANDEGG_BALANCE, ItemID.EASTER18_HANDEGG_CHAOS);

    /** The thrown handegg's in-flight projectile, one per handegg type. */
    static final Set<Integer> HANDEGG_PROJECTILES = Set.of(
        SpotanimID.EASTER18_HANDEGG_TRAVEL_SARA, SpotanimID.EASTER18_HANDEGG_TRAVEL_GUTH,
        SpotanimID.EASTER18_HANDEGG_TRAVEL_ZAM);

    /** Ticks after the projectile disappears in which a new holder still counts as the catch. */
    static final int LANDING_WINDOW_TICKS = 2;

    static final class Interception
    {
        final String receiver;
        final List<String> contacts;

        Interception(String receiver, List<String> contacts)
        {
            this.receiver = receiver;
            this.contacts = contacts;
        }
    }

    private Set<String> previousHolders = Collections.emptySet();
    private int lastInFlightTick = Integer.MIN_VALUE;

    /**
     * @param tick game tick count
     * @param ballInFlight a handegg projectile is drawn this tick
     * @param holders sanitized names of players holding a handegg this tick
     * @param contacts sanitized name to the names they are in contact with right now
     */
    List<Interception> onTick(int tick, boolean ballInFlight, Set<String> holders, Map<String, List<String>> contacts)
    {
        List<Interception> found = new ArrayList<>();
        if (ballInFlight)
        {
            lastInFlightTick = tick;
        }
        else if (lastInFlightTick != Integer.MIN_VALUE && tick - lastInFlightTick <= LANDING_WINDOW_TICKS)
        {
            for (String holder : holders)
            {
                if (previousHolders.contains(holder))
                {
                    continue;
                }
                // This throw is caught; whoever caught it, it can't be caught again.
                lastInFlightTick = Integer.MIN_VALUE;
                List<String> with = contacts.getOrDefault(holder, Collections.emptyList());
                if (!with.isEmpty())
                {
                    found.add(new Interception(holder, with));
                }
                break;
            }
        }
        previousHolders = new HashSet<>(holders);
        return found;
    }

    void reset()
    {
        previousHolders = Collections.emptySet();
        lastInFlightTick = Integer.MIN_VALUE;
    }
}
