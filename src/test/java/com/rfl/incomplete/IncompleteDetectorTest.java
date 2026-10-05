package com.rfl.incomplete;

import static com.rfl.Fixtures.mesh;
import static com.rfl.Fixtures.WALL;
import static com.rfl.Fixtures.THROUGH;
import static com.rfl.Fixtures.NEAR;
import com.rfl.contact.TileRef;
import com.rfl.Handegg;
import com.rfl.contact.ContactDetector;
import com.rfl.contact.ContactTracker;
import com.rfl.teams.Teams;
import sh.yumekui.toolkit.geom.TriangleMesh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import org.junit.Test;

/**
 * {@link IncompleteDetector} with a real {@link ContactTracker}, frame by frame in client cycles:
 * decided at the projectile's despawn, contact open or within 3 cycles before it, never after it,
 * opposite teams only.
 */
public class IncompleteDetectorTest
{

    /** A little house: who touches the receiver on which cycles, the throw, and the weapon slots. */
    private static final class Sim
    {
        final Map<String, Teams.Team> teams = new HashMap<>();
        /** Opponent name to the cycles their mesh crosses the receiver's. */
        final Map<String, IntPredicate> touching = new HashMap<>();
        /** Opponent name to the cycles their bounds overlap the receiver's with no triangle crossing. */
        final Map<String, IntPredicate> near = new HashMap<>();
        final ContactTracker tracker = new ContactTracker((x, y) -> TileRef.NONE, () -> 330);
        final IncompleteDetector detector = new IncompleteDetector();
        final List<IncompleteDetector.Call> found = new ArrayList<>();
        final String receiver;
        int throwFrom;
        /** First cycle the projectile is no longer drawn: the catch. */
        int despawn;
        /** Cycle the receiver's weapon slot shows the handegg (seen on the next game tick). */
        int gain;
        /** Whether the detector names the receiver as the flight's target while the ball flies. */
        boolean targetKnown;

        Sim(String receiver)
        {
            this.receiver = receiver;
            tracker.setPairFilter((a, b) -> Teams.opposing(teams.get(a), teams.get(b)));
        }

        void run(int from, int to)
        {
            for (int cyc = from; cyc <= to; cyc++)
            {
                if ((cyc - from) % 30 == 0)
                {
                    Set<String> holders = new HashSet<>();
                    if (cyc >= gain)
                    {
                        holders.add(receiver);
                    }
                    found.addAll(detector.onHolders(cyc, holders));
                }
                Map<String, TriangleMesh> meshes = new HashMap<>();
                meshes.put(receiver, mesh(0, WALL));
                double far = 1000;
                for (Map.Entry<String, IntPredicate> e : touching.entrySet())
                {
                    IntPredicate isNear = near.getOrDefault(e.getKey(), c -> false);
                    meshes.put(e.getKey(), e.getValue().test(cyc) ? mesh(0, THROUGH)
                        : isNear.test(cyc) ? mesh(0, NEAR) : mesh(far, THROUGH));
                    far += 1000;
                }
                tracker.setCycle(cyc);
                boolean flying = cyc >= throwFrom && cyc < despawn;
                tracker.setFlightReceivers(flying && targetKnown ? Set.of(receiver) : Set.of());
                // The receiver holds the egg from the gain on, so their pairs are handegg pairs.
                tracker.update(meshes, cyc >= gain ? Set.of(receiver) : Set.of(), cyc * 20L, cyc / 30, false);
                tracker.takeFinished();
                boolean inFlight = cyc >= throwFrom && cyc < despawn;
                final int at = cyc;
                found.addAll(detector.onFrame(cyc, cyc * 20L, cyc / 30, inFlight,
                    () -> tracker.contactsAt(at, IncompleteDetector.CONTACT_GRACE_CYCLES),
                    () -> Map.of(receiver, new int[] { at, at })));
            }
        }
    }

    private static IntPredicate between(int from, int to)
    {
        return c -> c >= from && c <= to;
    }

    @Test
    public void headlineGorillebCatchesInContactWithCorcrasNotJordanClark()
    {
        // tlg sc=36131: egg gone at 36187; Gorilleb touches Corcras 36181-36201 and 36207-36208,
        // nobody 36209-36219, then Jordan Clark from 36220. The old next-tick check (36211) saw nothing.
        Sim s = new Sim("Gorilleb");
        s.teams.put("Gorilleb", Teams.Team.A);
        s.teams.put("Corcras", Teams.Team.B);
        s.teams.put("Jordan Clark", Teams.Team.B);
        s.touching.put("Corcras", c -> c >= 36181 && c <= 36201 || c >= 36207 && c <= 36208);
        s.touching.put("Jordan Clark", c -> c >= 36220);
        s.throwFrom = 36091;
        s.gain = 36120;
        s.despawn = 36187;
        s.run(36090, 36260);
        assertEquals(1, s.found.size());
        IncompleteDetector.Call i = s.found.get(0);
        assertEquals("Gorilleb", i.receiver);
        assertEquals(List.of("Corcras"), i.contacts);
        assertEquals(36187, i.catchCycle);
        assertEquals(36187 * 20L, i.catchMs);
    }

    private static List<IncompleteDetector.Call> touchEndingBefore(int cyclesBefore)
    {
        Sim s = new Sim("Amy");
        s.teams.put("Amy", Teams.Team.A);
        s.teams.put("Zed", Teams.Team.B);
        s.throwFrom = 1001;
        s.gain = 1030;
        s.despawn = 1100;
        // One touching frame, then apart, so no collision is open at the catch.
        s.touching.put("Zed", between(1100 - cyclesBefore, 1100 - cyclesBefore));
        s.run(1000, 1130);
        return s.found;
    }

    @Test
    public void aTouchThreeCyclesBeforeTheCatchCountsFourDoesNot()
    {
        assertEquals(1, touchEndingBefore(3).size());
        assertEquals(1, touchEndingBefore(0).size());
        assertTrue(touchEndingBefore(4).isEmpty());
    }

    @Test
    public void contactThatStartsAfterTheCatchDoesNotCount()
    {
        Sim s = new Sim("Amy");
        s.teams.put("Amy", Teams.Team.A);
        s.teams.put("Zed", Teams.Team.B);
        s.throwFrom = 1001;
        s.gain = 1030;
        s.despawn = 1100;
        s.touching.put("Zed", c -> c >= 1101);
        s.run(1000, 1160);
        assertTrue(s.found.isEmpty());
    }

    @Test
    public void aLateWeaponPacketIsRuledOnTheContactsAtTheCatch()
    {
        // The slot shows the egg on the tick after the catch; contact then (but not at the catch) never counts.
        Sim late = new Sim("Amy");
        late.teams.put("Amy", Teams.Team.A);
        late.teams.put("Zed", Teams.Team.B);
        late.throwFrom = 1001;
        late.gain = 1110;
        late.despawn = 1100;
        late.touching.put("Zed", c -> c >= 1105);
        late.run(1000, 1160);
        assertTrue(late.found.isEmpty());
    }

    @Test
    public void aSameTeamContactDoesNotCount()
    {
        Sim s = new Sim("Amy");
        s.teams.put("Amy", Teams.Team.A);
        s.teams.put("Zed", Teams.Team.A);
        s.throwFrom = 1001;
        s.gain = 1030;
        s.despawn = 1100;
        s.touching.put("Zed", between(1050, 1150));
        s.run(1000, 1160);
        assertTrue(s.found.isEmpty());
    }

    @Test
    public void anOpenCollisionAtTheCatchCountsThroughFramesWithNoCrossingTriangles()
    {
        // Last crossing at 1090, then bounds still overlap without crossing until 1110: the
        // collision stays open over the catch at 1100, so it counts though no triangle touched within 3 cycles.
        Sim s = new Sim("Amy");
        s.teams.put("Amy", Teams.Team.A);
        s.teams.put("Zed", Teams.Team.B);
        s.throwFrom = 1001;
        s.gain = 1030;
        s.despawn = 1100;
        s.touching.put("Zed", between(1080, 1090));
        s.near.put("Zed", between(1091, 1110));
        s.run(1000, 1130);
        assertEquals(1, s.found.size());
        assertEquals(List.of("Zed"), s.found.get(0).contacts);
    }

    @Test
    public void anUncontestedCatchOrAPassWithoutAThrowDoesNotCount()
    {
        Sim s = new Sim("Amy");
        s.teams.put("Amy", Teams.Team.A);
        s.teams.put("Zed", Teams.Team.B);
        s.throwFrom = 2000;
        s.despawn = 2000;
        s.gain = 1030;
        s.touching.put("Zed", between(1000, 1200));
        s.run(1000, 1200);
        assertTrue("no projectile, no catch", s.found.isEmpty());
    }

    @Test
    public void holdersAreTheHandeggWeapons()
    {
        Map<String, Integer> weapons = new HashMap<>();
        weapons.put("Amy", Handegg.ITEMS.iterator().next());
        weapons.put("Zed", 4151);
        assertEquals(Set.of("Amy"), IncompleteDetector.holders(weapons));
    }

    private static Sim lateWeaponTouchDuringFlight(boolean targetKnown)
    {
        Sim s = new Sim("Amy");
        s.teams.put("Amy", Teams.Team.A);
        s.teams.put("Zed", Teams.Team.B);
        s.throwFrom = 1001;
        s.gain = 1110;
        s.despawn = 1100;
        s.targetKnown = targetKnown;
        // Zed touches Amy right up to the catch; Amy's slot only shows the egg a tick later.
        s.touching.put("Zed", between(1090, 1099));
        s.run(1000, 1160);
        return s;
    }

    @Test
    public void theReceiversContactDuringFlightIsTrackedWhenTheWeaponPacketIsLate()
    {
        Sim tracked = lateWeaponTouchDuringFlight(true);
        assertEquals(1, tracked.found.size());
        assertEquals(List.of("Zed"), tracked.found.get(0).contacts);
        assertEquals(1100, tracked.found.get(0).catchCycle);
        assertTrue("without the flight target nobody held an egg, so nothing was checked",
            lateWeaponTouchDuringFlight(false).found.isEmpty());
    }

    @Test
    public void noExtraPairsAreCheckedWithNoBallInFlightAndFlightTouchesNeverBecomeCollisions()
    {
        ContactTracker t = new ContactTracker((x, y) -> TileRef.NONE, () -> 330);
        Map<String, TriangleMesh> touching = Map.of("Amy", mesh(0, WALL), "Zed", mesh(0, THROUGH));
        t.setCycle(10);
        t.update(touching, Set.of(), 0, 1, true);
        assertTrue("no holder, no flight: not checked", t.contactsAt(10, 3).isEmpty());

        t.setFlightReceivers(Set.of("Amy"));
        t.setCycle(11);
        t.update(touching, Set.of(), 20, 1, true);
        assertEquals(List.of("Zed"), t.contactsAt(11, 3).get("Amy"));
        assertTrue("not drawn", t.overlaps().isEmpty());
        t.flush(30, 1);
        assertTrue("not a collision", t.takeFinished().isEmpty());

        t.setFlightReceivers(Set.of());
        t.setCycle(20);
        t.update(touching, Set.of(), 200, 2, true);
        assertTrue("flight over: unchecked again, and the old touch is past the grace", t.contactsAt(20, 3).isEmpty());
    }

    @Test
    public void endPointFallbackPicksPlayersWithinTwoTiles()
    {
        Map<String, int[]> at = new HashMap<>();
        at.put("Amy", new int[] { 1000, 1000 });
        at.put("Bo", new int[] { 1256, 744 });
        at.put("Zed", new int[] { 1257, 1000 });
        assertEquals(Set.of("Amy", "Bo"), ContactDetector.near(at, 1000, 1000, ContactDetector.RECEIVER_RADIUS));
    }

    @Test
    public void aLateWeaponPacketKeepsTheReceiversTileAtTheCatch()
    {
        // The Sim puts every player at local (cycle, cycle), so the tile shows which cycle it is from.
        Sim late = lateWeaponTouchDuringFlight(true);
        assertEquals(1, late.found.size());
        int[] at = late.found.get(0).receiverAt;
        assertEquals("the catch cycle, not the later tick the weapon packet arrived on", 1100, at[0]);
        assertEquals(1100, at[1]);
    }
}
