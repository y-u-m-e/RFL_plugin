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
 * Interception rule, checked on exactly one tick per throw: the tick the thrown handegg (any of
 * Holy, Peaceful or Chaotic) stops being drawn. On that tick, a player who has a handegg equipped
 * (and did not before the throw) and is colliding with another player right then intercepted it.
 * Hand-to-hand passes without a throw and uncontested catches do not count.
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

    /** Sanitized names of players with a handegg in the weapon slot. */
    static Set<String> holders(Map<String, Integer> weapons)
    {
        Set<String> holders = new HashSet<>();
        for (Map.Entry<String, Integer> entry : weapons.entrySet())
        {
            if (HANDEGG_ITEMS.contains(entry.getValue()))
            {
                holders.add(entry.getKey());
            }
        }
        return holders;
    }

    private Set<String> previousHolders = Collections.emptySet();
    /** Who held a handegg just before the current throw; none of them can be its catcher. */
    private Set<String> holdersBeforeThrow = Collections.emptySet();
    private boolean wasInFlight;
    /** What the last landing check saw and decided, for debug logging; null if none this tick. */
    private String lastCheck;

    /**
     * @param tick game tick count
     * @param ballInFlight a handegg projectile is drawn this tick
     * @param holders sanitized names of players with a handegg equipped this tick
     * @param colliding sanitized name to the names they are colliding with this tick
     */
    List<Interception> onTick(int tick, boolean ballInFlight, Set<String> holders, Map<String, List<String>> colliding)
    {
        List<Interception> found = new ArrayList<>();
        lastCheck = null;
        if (ballInFlight && !wasInFlight)
        {
            holdersBeforeThrow = previousHolders;
        }
        else if (!ballInFlight && wasInFlight)
        {
            // The tick the ball stops being drawn: the only tick this throw is checked.
            String decision = "no new holder";
            for (String holder : holders)
            {
                if (holdersBeforeThrow.contains(holder))
                {
                    continue;
                }
                List<String> with = colliding.getOrDefault(holder, Collections.emptyList());
                if (!with.isEmpty())
                {
                    found.add(new Interception(holder, with));
                    decision = "INTERCEPTION " + holder + " with " + with;
                    break;
                }
                decision = "uncontested catch by " + holder;
            }
            lastCheck = "landed: holders=" + holders + " beforeThrow=" + holdersBeforeThrow
                + " colliding=" + colliding + " -> " + decision;
        }
        wasInFlight = ballInFlight;
        previousHolders = new HashSet<>(holders);
        return found;
    }

    String lastCheck()
    {
        return lastCheck;
    }

    void reset()
    {
        previousHolders = Collections.emptySet();
        holdersBeforeThrow = Collections.emptySet();
        wasInFlight = false;
        lastCheck = null;
    }
}
