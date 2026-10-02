package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.google.gson.GsonBuilder;

/** Observer mode in {@link ContactTracker}: every pair, with names, kept apart from reported events. */
public class ObserverTrackerTest
{
    private static final double[][] WALL = {{40, -20, 40}, {40, 20, 40}, {40, 0, 80}};
    private static final double[][] THROUGH = {{30, 0, 50}, {50, 0, 50}, {40, 0, 70}};
    /** Inside WALL's bounds but clear of it. */
    private static final double[][] NEAR = {{30, 18, 70}, {50, 18, 70}, {40, 18, 80}};
    private static final double[][] FAR = {{300, 0, 50}, {320, 0, 50}, {310, 0, 70}};
    private static final Map<String, PosedMesh> NO_MESHES = Collections.emptyMap();

    private static ContactTracker tracker()
    {
        return new ContactTracker((x, y) -> new int[]{1000 + (int) Math.floor(x), 2000 + (int) Math.floor(y), 0},
            () -> 330);
    }

    private static PosedMesh mesh(double dx, double[]... triangle)
    {
        float[] x = new float[3];
        float[] y = new float[3];
        float[] z = new float[3];
        for (int k = 0; k < 3; k++)
        {
            x[k] = (float) (triangle[k][0] + dx);
            y[k] = (float) triangle[k][1];
            z[k] = (float) triangle[k][2];
        }
        return new PosedMesh(x, y, z, new int[]{0, 1, 2});
    }

    private static List<RflEvent> observe(ContactTracker t, Map<String, PosedMesh> meshes, String self,
        Set<String> holders, long now, int tick)
    {
        return t.update(meshes, self, holders, false, now, tick, false, true);
    }

    @Test
    public void tracksOtherOtherAndSelfPairsWithNamesAndBallHolders()
    {
        ContactTracker t = tracker();
        // A/B touch near x 40; Me/Amy touch 1000 units away.
        Map<String, PosedMesh> touching = Map.of("A", mesh(0, WALL), "B", mesh(0, THROUGH),
            "Me", mesh(1000, WALL), "Amy", mesh(1000, THROUGH));

        assertTrue(observe(t, touching, "Me", Set.of("B", "Me", "Amy"), 1000, 5).isEmpty());
        assertTrue(t.takeObserved().isEmpty());
        assertTrue(observe(t, NO_MESHES, "Me", Set.of(), 1600, 6).isEmpty());

        List<ObservedCollision> done = t.takeObserved();
        assertEquals(2, done.size());
        ObservedCollision ab = done.get(0).a.equals("A") ? done.get(0) : done.get(1);
        ObservedCollision self = ab == done.get(0) ? done.get(1) : done.get(0);

        assertEquals("A", ab.a);
        assertEquals("B", ab.b);
        assertEquals(List.of("B"), ab.ball);
        assertEquals(1000, ab.startMs);
        assertEquals(1600, ab.endMs);
        assertEquals(5, ab.startTick);
        assertEquals(6, ab.endTick);
        assertEquals(330, ab.world);
        assertEquals(1040, ab.x);
        assertEquals(2000, ab.y);
        assertEquals(0, ab.plane);
        assertEquals(1, ab.maxTriangles);

        assertEquals("Amy", self.a);
        assertEquals("Me", self.b);
        assertEquals("both holders are named", List.of("Amy", "Me"), self.ball);
        assertEquals(2040, self.x);
    }

    @Test
    public void startsOnlyWithAHandeggHolderInThePair()
    {
        ContactTracker t = tracker();
        Map<String, PosedMesh> touching = Map.of("A", mesh(0, WALL), "B", mesh(0, THROUGH));
        observe(t, touching, "Me", Set.of("Someone else"), 0, 0);
        observe(t, NO_MESHES, "Me", Set.of(), 20, 1);
        assertTrue(t.takeObserved().isEmpty());

        // Picking the handegg up mid-overlap starts it; dropping it doesn't end it, separating does.
        observe(t, touching, "Me", Set.of(), 40, 2);
        observe(t, touching, "Me", Set.of("A"), 60, 3);
        observe(t, touching, "Me", Set.of(), 80, 4);
        assertTrue(t.takeObserved().isEmpty());
        observe(t, Map.of("A", mesh(0, WALL), "B", mesh(0, FAR)), "Me", Set.of(), 100, 5);
        List<ObservedCollision> done = t.takeObserved();
        assertEquals(1, done.size());
        assertEquals(60, done.get(0).startMs);
        assertEquals(List.of("A"), done.get(0).ball);
    }

    @Test
    public void walkingThroughSomeoneIsOneCollision()
    {
        ContactTracker t = tracker();
        PosedMesh a = mesh(0, WALL);
        Set<String> holders = Set.of("A");
        observe(t, Map.of("A", a, "B", mesh(0, THROUGH)), "Me", holders, 0, 0);
        observe(t, Map.of("A", a, "B", mesh(0, NEAR)), "Me", holders, 20, 1);
        observe(t, Map.of("A", a, "B", mesh(0, THROUGH)), "Me", holders, 40, 2);
        assertTrue(t.takeObserved().isEmpty());
        observe(t, Map.of("A", a, "B", mesh(0, FAR)), "Me", holders, 60, 3);
        List<ObservedCollision> done = t.takeObserved();
        assertEquals(1, done.size());
        assertEquals(0, done.get(0).startMs);
        assertEquals(60, done.get(0).endMs);
    }

    @Test
    public void reportBuiltWhileObservingHasNoEventsAndNoNames()
    {
        ContactTracker t = tracker();
        Map<String, PosedMesh> touching = Map.of("Me", mesh(0, WALL), "Other Guy", mesh(0, THROUGH),
            "A", mesh(1000, WALL), "B", mesh(1000, THROUGH));
        Set<String> all = Set.of("Me", "Other Guy", "A", "B");

        List<RflEvent> events = observe(t, touching, "Me", all, 0, 0);
        events.addAll(observe(t, NO_MESHES, "Me", all, 20, 1));
        assertTrue(events.isEmpty());
        assertEquals(2, t.takeObserved().size());

        RflReport report = new RflReport("Me", "install-1", 330, 30L, true, "", List.of(), events,
            new RflReport.Features(true, true));
        String json = new GsonBuilder().create().toJson(report);
        assertTrue(json.contains("\"events\":[]"));
        assertFalse(json.contains("Other Guy"));
        assertFalse(json.contains("\"A\""));
    }

    @Test
    public void turningObserverOnEndsOpenSelfContactsAndOffFinishesOpenCollisions()
    {
        ContactTracker t = tracker();
        Map<String, PosedMesh> touching = Map.of("Me", mesh(0, WALL), "Zed", mesh(0, THROUGH));
        Set<String> holders = Set.of("Me");
        assertEquals("contact_start", t.update(touching, "Me", holders, false, 0, 0, false).get(0).type);

        List<RflEvent> on = observe(t, touching, "Me", holders, 20, 1);
        assertEquals(1, on.size());
        assertEquals("contact_end", on.get(0).type);
        assertTrue(observe(t, touching, "Me", holders, 40, 2).isEmpty());

        // Observer off with the pair still touching: the collision is saved as ended, and a
        // fresh self contact starts as normal.
        List<RflEvent> off = t.update(touching, "Me", holders, false, 60, 3, false);
        assertEquals("contact_start", off.get(0).type);
        List<ObservedCollision> done = t.takeObserved();
        assertEquals(1, done.size());
        assertEquals(20, done.get(0).startMs);
        assertEquals(60, done.get(0).endMs);
    }

    @Test
    public void flushFinishesOpenCollisionsAndResetDropsThem()
    {
        ContactTracker t = tracker();
        Map<String, PosedMesh> touching = Map.of("A", mesh(0, WALL), "B", mesh(0, THROUGH));
        observe(t, touching, "Me", Set.of("A"), 0, 0);
        t.flushObserved(99, 7);
        List<ObservedCollision> done = t.takeObserved();
        assertEquals(1, done.size());
        assertEquals(99, done.get(0).endMs);
        assertEquals(7, done.get(0).endTick);

        observe(t, touching, "Me", Set.of("A"), 100, 8);
        t.reset();
        t.flushObserved(200, 9);
        assertTrue(t.takeObserved().isEmpty());
    }

    @Test
    public void listsPairsBeingObservedUntilTheyFinish()
    {
        ContactTracker t = tracker();
        Map<String, PosedMesh> touching = Map.of("B", mesh(0, WALL), "A", mesh(0, THROUGH));
        observe(t, touching, "Me", Set.of("A"), 1000, 5);
        assertEquals(List.of("A ↔ B"), t.observing());
        observe(t, NO_MESHES, "Me", Set.of(), 1600, 6);
        assertTrue(t.observing().isEmpty());
    }
}
