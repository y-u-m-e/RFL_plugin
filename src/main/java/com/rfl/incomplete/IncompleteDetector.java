package com.rfl.incomplete;

import com.rfl.Handegg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Incomplete rule (owner rulings, docs/rfl-game-model.md): a throw is decided at the client cycle
 * its handegg projectile stops being drawn, the catch cycle. The receiver is the player whose
 * weapon slot gained a handegg for this throw. It is an incomplete when, at the catch cycle, the
 * receiver is in an open collision with an opposing player, or touched one within
 * {@link #CONTACT_GRACE_CYCLES} cycles before it. Contact that only begins after the catch never
 * counts. Only opposite teams can be in contact at all (the tracker's team gate).
 *
 * <p>Two clocks feed it. {@link #onHolders} runs each GameTick, when weapon slots change, and
 * records when each player gained a handegg. {@link #onFrame} runs each ClientTick; only the frame
 * the projectile disappears does any work. The server hands the egg over before the cosmetic
 * flight ends, often before the projectile even starts, so a gain from up to
 * {@link #GAIN_BEFORE_THROW_CYCLES} before the throw was first drawn still names the receiver. A
 * weapon packet that arrives late (up to {@link #LATE_WEAPON_CYCLES} after the catch) is ruled
 * against the contacts as they were at the catch, never later ones. Hand-to-hand passes without a
 * throw and uncontested catches do not count.
 *
 * <p>Threads: client thread only. No client access; unit-tested on its own.
 */
public final class IncompleteDetector
{
    /** A touch this many client cycles (20 ms each, so 60 ms) before the catch still counts. */
    static final int CONTACT_GRACE_CYCLES = 3;
    /** A handegg gained up to this long before the throw was first drawn can be this throw's catch. */
    static final int GAIN_BEFORE_THROW_CYCLES = 60;
    /** How long after the catch a late weapon packet may still name the receiver: one game tick. */
    static final int LATE_WEAPON_CYCLES = 30;

    /** The rule's call: an incomplete, who caught it, against whom, and when. */
    static final class Call
    {
        final String receiver;
        final List<String> contacts;
        /** Client cycle the projectile disappeared: the catch. */
        final int catchCycle;
        /** Wall-clock ms of the frame that saw the catch. */
        final long catchMs;
        /** Game tick count at the catch. */
        final int catchTick;
        /** The receiver's local {x, y} at the catch, or null when they were not in view then. */
        final int[] receiverAt;

        Call(String receiver, List<String> contacts, int catchCycle, long catchMs, int catchTick,
            int[] receiverAt)
        {
            this.receiver = receiver;
            this.contacts = contacts;
            this.catchCycle = catchCycle;
            this.catchMs = catchMs;
            this.catchTick = catchTick;
            this.receiverAt = receiverAt;
        }
    }

    /** Sanitized names of players with a handegg in the weapon slot. */
    static Set<String> holders(Map<String, Integer> weapons)
    {
        Set<String> holders = new HashSet<>();
        for (Map.Entry<String, Integer> entry : weapons.entrySet())
        {
            if (Handegg.isHandegg(entry.getValue()))
            {
                holders.add(entry.getKey());
            }
        }
        return holders;
    }

    /** A catch whose receiver's weapon packet hasn't arrived yet. */
    private static final class Pending
    {
        final int throwCycle;
        final int catchCycle;
        final long catchMs;
        final int catchTick;
        /** Everyone's qualifying contacts at the catch cycle. */
        final Map<String, List<String>> contacts;
        /** Everyone's local {x, y} at the catch cycle. */
        final Map<String, int[]> positions;

        Pending(int throwCycle, int catchCycle, long catchMs, int catchTick, Map<String, List<String>> contacts,
            Map<String, int[]> positions)
        {
            this.throwCycle = throwCycle;
            this.catchCycle = catchCycle;
            this.catchMs = catchMs;
            this.catchTick = catchTick;
            this.contacts = contacts;
            this.positions = positions;
        }
    }

    /** Current holders to the client cycle they gained the handegg. */
    private Map<String, Integer> gained = new HashMap<>();
    private boolean wasInFlight;
    /** Client cycle the current throw's projectile was first drawn. */
    private int throwCycle;
    private Pending pending;

    /**
     * GameTick: the weapon slots now. Records new holders with {@code cycle}, forgets players who
     * no longer hold one, and rules a pending catch if its receiver just showed up.
     */
    List<Call> onHolders(int cycle, Set<String> holders)
    {
        Map<String, Integer> next = new HashMap<>();
        for (String holder : holders)
        {
            Integer since = gained.get(holder);
            next.put(holder, since == null ? cycle : since);
        }
        gained = next;
        if (pending == null)
        {
            return Collections.emptyList();
        }
        Pending caught = pending;
        String receiver = receiver(caught.throwCycle);
        if (receiver == null)
        {
            if (cycle - caught.catchCycle > LATE_WEAPON_CYCLES)
            {
                pending = null;
            }
            return Collections.emptyList();
        }
        pending = null;
        return rule(receiver, caught.contacts.get(receiver), caught.catchCycle, caught.catchMs, caught.catchTick,
            caught.positions.get(receiver));
    }

    /**
     * ClientTick, after the frame's contact update. Free on every frame but the one the projectile
     * disappears, which asks {@code contactsAtCatch} once.
     *
     * @param ballInFlight a handegg projectile is drawn this frame
     * @param contactsAtCatch everyone's qualifying contacts now (open collisions, or touched within
     *     {@link #CONTACT_GRACE_CYCLES}); only called on the catch frame
     * @param positionsAtCatch everyone's local {x, y} now; only called on the catch frame, so a
     *     receiver named by a late weapon packet still gets the tile they caught it on
     */
    List<Call> onFrame(int cycle, long nowMs, int tick, boolean ballInFlight,
        Supplier<Map<String, List<String>>> contactsAtCatch, Supplier<Map<String, int[]>> positionsAtCatch)
    {
        boolean caught = wasInFlight && !ballInFlight;
        if (ballInFlight && !wasInFlight)
        {
            throwCycle = cycle;
            pending = null;
        }
        wasInFlight = ballInFlight;
        if (!caught)
        {
            return Collections.emptyList();
        }
        Map<String, List<String>> contacts = contactsAtCatch.get();
        Map<String, int[]> positions = positionsAtCatch.get();
        String receiver = receiver(throwCycle);
        if (receiver == null)
        {
            pending = new Pending(throwCycle, cycle, nowMs, tick, contacts, positions);
            return Collections.emptyList();
        }
        return rule(receiver, contacts.get(receiver), cycle, nowMs, tick, positions.get(receiver));
    }

    /** The newest holder who gained the egg no earlier than {@link #GAIN_BEFORE_THROW_CYCLES} before the throw. */
    private String receiver(int throwAt)
    {
        String best = null;
        int bestAt = Integer.MIN_VALUE;
        for (Map.Entry<String, Integer> gain : gained.entrySet())
        {
            int at = gain.getValue();
            if (at >= throwAt - GAIN_BEFORE_THROW_CYCLES && at > bestAt)
            {
                best = gain.getKey();
                bestAt = at;
            }
        }
        return best;
    }

    private static List<Call> rule(String receiver, List<String> with, int catchCycle, long catchMs,
        int catchTick, int[] receiverAt)
    {
        if (with == null || with.isEmpty())
        {
            return Collections.emptyList();
        }
        List<Call> out = new ArrayList<>(1);
        out.add(new Call(receiver, with, catchCycle, catchMs, catchTick, receiverAt));
        return out;
    }

    void reset()
    {
        gained = new HashMap<>();
        wasInFlight = false;
        throwCycle = 0;
        pending = null;
    }
}
