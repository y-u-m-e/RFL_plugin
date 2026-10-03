package com.rfl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.rfl.ReplaySampler.Appearance;
import com.rfl.ReplaySampler.Ball;
import com.rfl.ReplaySampler.PlayerState;

/**
 * {@link ReplaySampler}: turns per-cycle player/ball/appearance state into the NDJSON line
 * objects, with no RuneLite dependency. Covers index stability across despawn/reorder and
 * delta-only rows (spec Review Focus 4-5).
 */
public class ReplaySamplerTest
{
    private static PlayerState player(String name, int x, int y, int orient, int anim, int animFrame, int pose,
        int poseFrame)
    {
        return new PlayerState(name, x, y, orient, anim, animFrame, pose, poseFrame);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rows(Map<String, Object> fLine)
    {
        return (List<Object>) fLine.get("p");
    }

    private static Map<String, Object> firstOfType(List<Map<String, Object>> lines, String type)
    {
        return lines.stream().filter(l -> type.equals(l.get("t"))).findFirst()
            .orElseThrow(() -> new AssertionError("no " + type + " line in " + lines));
    }

    @Test
    public void firstFrameSpawnsAndWritesEveryone()
    {
        ReplaySampler sampler = new ReplaySampler();
        List<PlayerState> players = List.of(
            player("A", 100, 200, 0, 1, 2, 3, 4),
            player("B", 110, 210, 1, 2, 3, 4, 5));

        List<Map<String, Object>> lines = sampler.frame(1, players, List.of());

        assertEquals(3, lines.size());
        assertEquals("spawn", lines.get(0).get("t"));
        assertEquals(0, lines.get(0).get("i"));
        assertEquals("A", lines.get(0).get("name"));
        assertEquals("spawn", lines.get(1).get("t"));
        assertEquals(1, lines.get(1).get("i"));
        assertEquals("B", lines.get(1).get("name"));
        assertEquals("f", lines.get(2).get("t"));
        assertEquals(2, rows(lines.get(2)).size());
        assertArrayEquals(new int[] { 0, 100, 200, 0, 1, 2, 3, 4 }, (int[]) rows(lines.get(2)).get(0));
        assertArrayEquals(new int[] { 1, 110, 210, 1, 2, 3, 4, 5 }, (int[]) rows(lines.get(2)).get(1));
    }

    @Test
    public void unchangedPlayerWritesNoRow()
    {
        ReplaySampler sampler = new ReplaySampler();
        List<PlayerState> players = List.of(player("A", 100, 200, 0, 1, 2, 3, 4));
        sampler.frame(1, players, List.of());

        List<Map<String, Object>> lines = sampler.frame(2, players, List.of());

        assertTrue(lines.isEmpty());
    }

    @Test
    public void onlyChangedPlayersAppearInF()
    {
        ReplaySampler sampler = new ReplaySampler();
        List<PlayerState> initial = List.of(
            player("A", 100, 200, 0, 1, 2, 3, 4),
            player("B", 110, 210, 1, 2, 3, 4, 5));
        sampler.frame(1, initial, List.of());

        List<PlayerState> onlyBChanged = List.of(
            player("A", 100, 200, 0, 1, 2, 3, 4),
            player("B", 110, 210, 1, 2, 3, 4, 9));

        List<Map<String, Object>> lines = sampler.frame(2, onlyBChanged, List.of());

        assertEquals(1, lines.size());
        List<Object> p = rows(lines.get(0));
        assertEquals(1, p.size());
        assertEquals(1, ((int[]) p.get(0))[0]);
    }

    @Test
    public void indexStableAcrossDespawnAndReorder()
    {
        ReplaySampler sampler = new ReplaySampler();
        sampler.frame(1, List.of(
            player("A", 100, 200, 0, 1, 2, 3, 4),
            player("B", 110, 210, 1, 2, 3, 4, 5)), List.of());

        List<Map<String, Object>> cycle2 = sampler.frame(2, List.of(
            player("B", 110, 210, 1, 2, 3, 4, 5)), List.of());

        assertEquals(1, cycle2.size());
        assertEquals("despawn", cycle2.get(0).get("t"));
        assertEquals(0, cycle2.get(0).get("i"));

        List<Map<String, Object>> cycle3 = sampler.frame(3, List.of(
            player("B", 110, 210, 1, 2, 3, 4, 5),
            player("A", 100, 200, 0, 1, 2, 3, 4)), List.of());

        Map<String, Object> spawn = firstOfType(cycle3, "spawn");
        assertEquals(0, spawn.get("i"));
        assertEquals("A", spawn.get("name"));

        Map<String, Object> f = firstOfType(cycle3, "f");
        assertTrue(rows(f).stream().anyMatch(o -> ((int[]) o)[0] == 0));

        assertEquals(0, sampler.indexOf("A"));
    }

    @Test
    public void deltaComparesToLastWrittenNotLastCycle()
    {
        ReplaySampler sampler = new ReplaySampler();
        sampler.frame(1, List.of(player("A", 100, 200, 0, 1, 2, 3, 4)), List.of());

        List<Map<String, Object>> cycle50 = sampler.frame(50, List.of(player("A", 100, 200, 0, 1, 2, 3, 4)),
            List.of());
        assertTrue(cycle50.isEmpty());

        List<Map<String, Object>> cycle51 = sampler.frame(51, List.of(player("A", 228, 200, 0, 1, 2, 3, 4)),
            List.of());

        assertEquals(1, cycle51.size());
        List<Object> rows = rows(cycle51.get(0));
        assertEquals(1, rows.size());
        assertEquals(228, ((int[]) rows.get(0))[1]);
    }

    @Test
    public void ballLinesEveryCycleWhileInFlight()
    {
        ReplaySampler sampler = new ReplaySampler();
        Ball ball = new Ball(7, 1, 1.5, 2.5, 0.0, 90);

        List<Map<String, Object>> cycle1 = sampler.frame(1, List.of(), List.of(ball));
        assertEquals(1, cycle1.size());
        Map<String, Object> line = cycle1.get(0);
        assertEquals("ball", line.get("t"));
        assertEquals(1, line.get("cyc"));
        assertEquals(7, line.get("id"));
        assertEquals(1, line.get("sc"));
        assertEquals(1.5, line.get("x"));
        assertEquals(2.5, line.get("y"));
        assertEquals(0.0, line.get("z"));
        assertEquals(90, line.get("o"));

        List<Map<String, Object>> cycle2 = sampler.frame(2, List.of(), List.of(ball));
        assertEquals(1, cycle2.size());

        List<Map<String, Object>> cycle3 = sampler.frame(3, List.of(), List.of());
        assertTrue(cycle3.isEmpty());
    }

    @Test
    public void appearanceOnlyWhenChanged()
    {
        ReplaySampler sampler = new ReplaySampler();
        int[] eq = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };
        int[] col = { 1, 2, 3, 4, 5 };
        Appearance a = new Appearance("A", 0, eq, col);

        List<Map<String, Object>> tick1 = sampler.tick(1, 100, List.of(a));
        assertEquals(2, tick1.size());
        assertEquals("tick", tick1.get(0).get("t"));
        assertEquals(1, tick1.get(0).get("cyc"));
        assertEquals(100, tick1.get(0).get("tick"));
        assertEquals("app", tick1.get(1).get("t"));
        assertArrayEquals(eq, (int[]) tick1.get(1).get("eq"));
        assertArrayEquals(col, (int[]) tick1.get(1).get("col"));

        List<Map<String, Object>> tick2 = sampler.tick(2, 101, List.of(a));
        assertEquals(1, tick2.size());
        assertEquals("tick", tick2.get(0).get("t"));

        int[] eq2 = eq.clone();
        eq2[3] = 999;
        Appearance changed = new Appearance("A", 0, eq2, col);
        List<Map<String, Object>> tick3 = sampler.tick(3, 102, List.of(changed));
        assertEquals(2, tick3.size());
        assertEquals("app", tick3.get(1).get("t"));
        assertArrayEquals(eq2, (int[]) tick3.get(1).get("eq"));
    }
}
