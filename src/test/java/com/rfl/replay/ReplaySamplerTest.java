package com.rfl.replay;

import com.rfl.replay.pitch.PitchFloor;
import com.rfl.replay.pitch.PitchObjects;
import sh.yumekui.toolkit.model.ModelCapture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.Test;

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
        return new PlayerState(name, x, y, orient, anim, animFrame, pose, poseFrame, PlayerState.NO_SPOTS, null, null);
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
    public void noBallLinesBeforeTheProjectileStarts()
    {
        // The client creates the projectile ~0.8 s early, parked at (0, 0) through its start cycle;
        // the first real position is the cycle after (seen in a real recording).
        ReplaySampler sampler = new ReplaySampler();
        Ball parked = new Ball(1528, 100, 0.0, 0.0, 0.0, 0, null);
        assertTrue(sampler.frame(99, List.of(), List.of(parked)).isEmpty());
        assertTrue(sampler.frame(100, List.of(), List.of(parked)).isEmpty());
        assertEquals(1, sampler.frame(101, List.of(), List.of(new Ball(1528, 100, 8896, 5696, -651, 1175, null))).size());
    }

    @Test
    public void ballLinesEveryCycleWhileInFlight()
    {
        ReplaySampler sampler = new ReplaySampler();
        // Started at cycle 0, so it is in flight (has a position) from cycle 1.
        Ball ball = new Ball(7, 0, 1.5, 2.5, 0.0, 90, null);

        List<Map<String, Object>> cycle1 = sampler.frame(1, List.of(), List.of(ball));
        assertEquals(1, cycle1.size());
        Map<String, Object> line = cycle1.get(0);
        assertEquals("ball", line.get("t"));
        assertEquals(1, line.get("cyc"));
        assertEquals(7, line.get("id"));
        assertEquals(0, line.get("sc"));
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
        sampler.frame(1, List.of(player("A", 100, 200, 0, 1, 2, 3, 4)), List.of());
        int[] eq = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };
        int[] col = { 1, 2, 3, 4, 5 };
        Appearance a = new Appearance("A", 0, eq, col);

        List<Map<String, Object>> tick1 = sampler.tick(1, 100, List.of(a), List.of());
        assertEquals(2, tick1.size());
        assertEquals("tick", tick1.get(0).get("t"));
        assertEquals(1, tick1.get(0).get("cyc"));
        assertEquals(100, tick1.get(0).get("tick"));
        assertEquals("app", tick1.get(1).get("t"));
        assertArrayEquals(eq, (int[]) tick1.get(1).get("eq"));
        assertArrayEquals(col, (int[]) tick1.get(1).get("col"));

        List<Map<String, Object>> tick2 = sampler.tick(2, 101, List.of(a), List.of());
        assertEquals(1, tick2.size());
        assertEquals("tick", tick2.get(0).get("t"));

        int[] eq2 = eq.clone();
        eq2[3] = 999;
        Appearance changed = new Appearance("A", 0, eq2, col);
        List<Map<String, Object>> tick3 = sampler.tick(3, 102, List.of(changed), List.of());
        assertEquals(2, tick3.size());
        assertEquals("app", tick3.get(1).get("t"));
        assertArrayEquals(eq2, (int[]) tick3.get(1).get("eq"));
    }

    @Test
    public void appearanceWaitsForSpawn()
    {
        ReplaySampler sampler = new ReplaySampler();
        int[] eq = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };
        int[] col = { 1, 2, 3, 4, 5 };
        Appearance a = new Appearance("A", 0, eq, col);

        List<Map<String, Object>> beforeSpawn = sampler.tick(1, 100, List.of(a), List.of());
        assertEquals(1, beforeSpawn.size());
        assertEquals("tick", beforeSpawn.get(0).get("t"));
        assertEquals(-1, sampler.indexOf("A"));

        List<Map<String, Object>> spawnFrame = sampler.frame(1, List.of(player("A", 100, 200, 0, 1, 2, 3, 4)),
            List.of());
        assertEquals(0, firstOfType(spawnFrame, "spawn").get("i"));

        List<Map<String, Object>> afterSpawn = sampler.tick(2, 101, List.of(a), List.of());
        assertEquals(2, afterSpawn.size());
        assertEquals("app", afterSpawn.get(1).get("t"));
        assertEquals(0, afterSpawn.get(1).get("i"));

        sampler.frame(2, List.of(), List.of());
        sampler.frame(3, List.of(player("A", 100, 200, 0, 1, 2, 3, 4)), List.of());

        List<Map<String, Object>> afterRespawn = sampler.tick(3, 102, List.of(a), List.of());
        assertEquals(2, afterRespawn.size());
        assertEquals("app", afterRespawn.get(1).get("t"));
    }

    private static PlayerState spotted(String name, int... spots)
    {
        return new PlayerState(name, 100, 200, 0, 1, 2, 3, 4, spots, null, null);
    }

    private static long count(List<Map<String, Object>> lines, String type)
    {
        return lines.stream().filter(l -> type.equals(l.get("t"))).count();
    }

    @Test
    public void trueTileWritesOnlyChangedPlayers()
    {
        ReplaySampler sampler = new ReplaySampler();
        sampler.frame(1, List.of(player("A", 100, 200, 0, 1, 2, 3, 4), player("B", 110, 210, 1, 2, 3, 4, 5)),
            List.of());

        List<Map<String, Object>> tick1 = sampler.tick(1, 100, List.of(),
            List.of(new TrueTile("A", 64, 192), new TrueTile("B", 192, 320)));
        Map<String, Object> tt1 = firstOfType(tick1, "tt");
        assertEquals(1, tt1.get("cyc"));
        assertEquals(2, rows(tt1).size());
        assertArrayEquals(new int[] { 0, 64, 192 }, (int[]) rows(tt1).get(0));
        assertArrayEquals(new int[] { 1, 192, 320 }, (int[]) rows(tt1).get(1));

        List<Map<String, Object>> tick2 = sampler.tick(2, 101, List.of(),
            List.of(new TrueTile("A", 64, 192), new TrueTile("B", 320, 320)));
        Map<String, Object> tt2 = firstOfType(tick2, "tt");
        assertEquals(1, rows(tt2).size());
        assertArrayEquals(new int[] { 1, 320, 320 }, (int[]) rows(tt2).get(0));
    }

    @Test
    public void trueTileUnchangedWritesNoLine()
    {
        ReplaySampler sampler = new ReplaySampler();
        sampler.frame(1, List.of(player("A", 100, 200, 0, 1, 2, 3, 4)), List.of());
        sampler.tick(1, 100, List.of(), List.of(new TrueTile("A", 64, 192)));

        List<Map<String, Object>> tick2 = sampler.tick(2, 101, List.of(), List.of(new TrueTile("A", 64, 192)));

        assertEquals(0, count(tick2, "tt"));
        assertEquals(1, tick2.size());
    }

    @Test
    public void trueTileWaitsForSpawnAndDespawnClears()
    {
        ReplaySampler sampler = new ReplaySampler();
        TrueTile a = new TrueTile("A", 64, 192);

        assertEquals(0, count(sampler.tick(1, 100, List.of(), List.of(a)), "tt"));
        assertEquals(-1, sampler.indexOf("A"));

        sampler.frame(1, List.of(player("A", 100, 200, 0, 1, 2, 3, 4)), List.of());
        assertEquals(1, count(sampler.tick(2, 101, List.of(), List.of(a)), "tt"));

        sampler.frame(2, List.of(), List.of());
        assertEquals(0, count(sampler.tick(3, 102, List.of(), List.of(a)), "tt"));

        sampler.frame(3, List.of(player("A", 100, 200, 0, 1, 2, 3, 4)), List.of());
        Map<String, Object> tt = firstOfType(sampler.tick(4, 103, List.of(), List.of(a)), "tt");
        assertArrayEquals(new int[] { 0, 64, 192 }, (int[]) rows(tt).get(0));
    }

    @Test
    public void spotAnimChangeWritesLine()
    {
        ReplaySampler sampler = new ReplaySampler();
        sampler.frame(1, List.of(spotted("A")), List.of());

        List<Map<String, Object>> lines = sampler.frame(2, List.of(spotted("A", 1234, 3, 92)), List.of());

        Map<String, Object> spot = firstOfType(lines, "spot");
        assertEquals(2, spot.get("cyc"));
        assertEquals(0, spot.get("i"));
        @SuppressWarnings("unchecked")
        List<Object> s = (List<Object>) spot.get("s");
        assertEquals(1, s.size());
        assertArrayEquals(new int[] { 1234, 3, 92 }, (int[]) s.get(0));
    }

    @Test
    public void spotAnimUnchangedWritesNoLine()
    {
        ReplaySampler sampler = new ReplaySampler();
        sampler.frame(1, List.of(spotted("A", 1234, 3, 92, 50, 0, 0)), List.of());

        // Same set in a different iteration order is no change.
        List<Map<String, Object>> lines = sampler.frame(2, List.of(spotted("A", 50, 0, 0, 1234, 3, 92)), List.of());

        assertTrue(lines.isEmpty());
    }

    @Test
    public void spotAnimNoneOnSpawnWritesNoLine()
    {
        ReplaySampler sampler = new ReplaySampler();
        assertEquals(0, count(sampler.frame(1, List.of(spotted("A")), List.of()), "spot"));
    }

    @Test
    public void spotAnimClearedWritesEmptySet()
    {
        ReplaySampler sampler = new ReplaySampler();
        sampler.frame(1, List.of(spotted("A", 1234, 3, 92)), List.of());

        Map<String, Object> spot = firstOfType(sampler.frame(2, List.of(spotted("A")), List.of()), "spot");

        assertEquals(0, spot.get("i"));
        assertTrue(((List<?>) spot.get("s")).isEmpty());
    }

    @Test
    public void spotAnimRespawnWritesAgain()
    {
        ReplaySampler sampler = new ReplaySampler();
        sampler.frame(1, List.of(spotted("A", 1234, 3, 92)), List.of());
        sampler.frame(2, List.of(), List.of());

        List<Map<String, Object>> lines = sampler.frame(3, List.of(spotted("A", 1234, 3, 92)), List.of());

        assertEquals(1, count(lines, "spot"));
    }

    @Test
    public void pitchLineSampleSize() throws Exception
    {
        // A worst-case-ish pitch: full 41x41 floor crops, 400 objects, all four planes of chunks.
        Map<String, Object> line = new java.util.LinkedHashMap<>();
        java.util.Random rnd = new java.util.Random(1);
        int[][] heights = new int[104][104];
        for (int[] col : heights)
        {
            for (int y = 0; y < col.length; y++)
            {
                col[y] = -rnd.nextInt(2000);
            }
        }
        int[][][] chunks = new int[4][13][13];
        for (int[][] p : chunks)
        {
            for (int[] col : p)
            {
                for (int y = 0; y < col.length; y++)
                {
                    col[y] = rnd.nextInt();
                }
            }
        }
        short[][] under = new short[104][104];
        short[][] over = new short[104][104];
        byte[][] shapes = new byte[104][104];
        for (int x = 0; x < 104; x++)
        {
            for (int y = 0; y < 104; y++)
            {
                under[x][y] = (short) (1 + rnd.nextInt(100));
                over[x][y] = (short) rnd.nextInt(150);
                shapes[x][y] = (byte) rnd.nextInt(12);
            }
        }
        PitchObjects objs = new PitchObjects();
        for (int i = 0; i < 400; i++)
        {
            int x = 6400 + rnd.nextInt(5000);
            int y = 6400 + rnd.nextInt(5000);
            objs.add(i % 4, i, rnd.nextInt(60000), rnd.nextInt(2048), x, y, rnd.nextInt(1 << 8), x, y, 1, 1);
        }
        line.put("t", "pitch");
        line.put("cyc", 123456);
        line.put("plane", 0);
        line.put("baseX", 1856);
        line.put("baseY", 5056);
        line.put("chunks", chunks[0]);
        line.put("heights", heights);
        line.put("objs", objs.rows());
        line.put("objs2", objs.rows2());
        line.put("under", PitchFloor.crop(under, 52, 52, 20));
        line.put("over", PitchFloor.crop(over, 52, 52, 20));
        line.put("shapes", PitchFloor.crop(shapes, 52, 52, 20));
        line.put("rots", PitchFloor.crop(shapes, 52, 52, 20));
        line.put("chunksAll", chunks);

        byte[] json = new com.google.gson.Gson().toJson(line).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bytes))
        {
            gz.write(json);
        }
        System.out.println("pitch sample: " + json.length + " bytes raw, " + bytes.size() + " bytes gzipped");
        assertTrue(json.length < 400_000);
    }

    // ---- P6: recorded models (spec §2.3) ----

    /** A fake model: {@code n} vertices offset by {@code shift}, one face, grey. */
    private static ModelCapture.Geometry geometry(int n, int shift)
    {
        int[] v = new int[n * 3];
        for (int k = 0; k < v.length; k++)
        {
            v[k] = k + shift;
        }
        return new ModelCapture.Geometry(v, new int[] { 0, 1, 2 }, new int[] { 10, 20, 30 });
    }

    /** A supplier that counts how often the sampler asks it to capture. */
    private static Supplier<ModelCapture.Geometry> counting(AtomicInteger calls, ModelCapture.Geometry g)
    {
        return () ->
        {
            calls.incrementAndGet();
            return g;
        };
    }

    private static PlayerState posed(String name, int anim, Supplier<ModelCapture.Geometry> model)
    {
        return new PlayerState(name, 100, 200, 0, anim, 0, 808, 0, PlayerState.NO_SPOTS, model, null);
    }

    private static Appearance look(String name, int gender)
    {
        return new Appearance(name, gender, new int[] { 1, 2, 3 }, new int[] { 4, 5 });
    }

    private static List<String> types(List<Map<String, Object>> lines)
    {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> l : lines)
        {
            out.add((String) l.get("t"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<int[]> pmRows(List<Map<String, Object>> lines)
    {
        return (List<int[]>) firstOfType(lines, "pm").get("p");
    }

    @Test
    public void pmOnlyWhenModelChanges()
    {
        ReplaySampler sampler = new ReplaySampler();
        AtomicInteger calls = new AtomicInteger();
        sampler.tick(1, 1, List.of(look("A", 0)), List.of());

        List<Map<String, Object>> first = sampler.frame(1, List.of(posed("A", 1, counting(calls, geometry(3, 0)))),
            List.of());
        assertEquals(1, pmRows(first).size());
        assertArrayEquals(new int[] { 0, 0 }, pmRows(first).get(0));

        // Same key again: no pm, no capture.
        List<Map<String, Object>> same = sampler.frame(2, List.of(posed("A", 1, counting(calls, geometry(3, 0)))),
            List.of());
        assertFalse(types(same).contains("pm"));

        // New anim: new model id 1.
        List<Map<String, Object>> moved = sampler.frame(3, List.of(posed("A", 2, counting(calls, geometry(3, 5)))),
            List.of());
        assertArrayEquals(new int[] { 0, 1 }, pmRows(moved).get(0));

        // Back to the first tuple: pm points at id 0 again, nothing captured.
        List<Map<String, Object>> back = sampler.frame(4, List.of(posed("A", 1, counting(calls, geometry(3, 9)))),
            List.of());
        assertArrayEquals(new int[] { 0, 0 }, pmRows(back).get(0));
        assertFalse(types(back).contains("model"));
        assertEquals(2, calls.get());
    }

    @Test
    public void modelLineWrittenOncePerKey()
    {
        ReplaySampler sampler = new ReplaySampler();
        AtomicInteger calls = new AtomicInteger();
        sampler.tick(1, 1, List.of(look("A", 0), look("B", 0)), List.of());

        // Two players, same appearance and tuple: one key, one model line, before the pm that uses it.
        List<Map<String, Object>> lines = sampler.frame(1, List.of(
            posed("A", 1, counting(calls, geometry(3, 0))),
            posed("B", 1, counting(calls, geometry(3, 0)))), List.of());

        assertEquals(1, calls.get());
        assertEquals(1, types(lines).stream().filter("model"::equals).count());
        assertTrue(types(lines).indexOf("model") < types(lines).indexOf("pm"));
        Map<String, Object> model = firstOfType(lines, "model");
        assertEquals(0, model.get("id"));
        assertEquals("player", model.get("kind"));
        assertArrayEquals(new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8 }, (int[]) model.get("v"));
        assertArrayEquals(new int[] { 0, 1, 2 }, (int[]) model.get("f"));
        assertArrayEquals(new int[] { 10, 20, 30 }, (int[]) model.get("c"));
        assertEquals(2, pmRows(lines).size());

        sampler.frame(2, List.of(posed("A", 1, counting(calls, geometry(3, 0)))), List.of());
        assertEquals(1, calls.get());
        assertEquals(1, sampler.modelsCaptured());
    }

    @Test
    public void ballRowCarriesModelId()
    {
        ReplaySampler sampler = new ReplaySampler();
        AtomicInteger calls = new AtomicInteger();
        Ball ball = new Ball(1528, 10, 6500.5, 6600.1, -320.0, 256, counting(calls, geometry(4, 0)));

        assertTrue(sampler.frame(10, List.of(), List.of(ball)).isEmpty());
        List<Map<String, Object>> lines = sampler.frame(11, List.of(), List.of(ball));

        assertEquals(List.of("model", "ball"), types(lines));
        assertEquals("ball", lines.get(0).get("kind"));
        assertEquals(0, lines.get(1).get("m"));

        List<Map<String, Object>> next = sampler.frame(12, List.of(), List.of(ball));
        assertEquals(List.of("ball"), types(next));
        assertEquals(0, next.get(0).get("m"));
        assertEquals(1, calls.get());

        // No model available: the row has no m.
        Ball bare = new Ball(1529, 10, 1, 2, 3, 0, null);
        assertFalse(sampler.frame(13, List.of(), List.of(bare)).get(0).containsKey("m"));
    }

    @Test
    public void laterPoseOfAnAppearanceIsDeltaEncoded()
    {
        ReplaySampler sampler = new ReplaySampler();
        sampler.tick(1, 1, List.of(look("A", 0)), List.of());
        sampler.frame(1, List.of(posed("A", 1, () -> geometry(3, 0))), List.of());

        Map<String, Object> delta = firstOfType(
            sampler.frame(2, List.of(posed("A", 2, () -> geometry(3, 4))), List.of()), "model");
        assertEquals(1, delta.get("id"));
        assertEquals(0, delta.get("base"));
        assertArrayEquals(new int[] { 4, 4, 4, 4, 4, 4, 4, 4, 4 }, (int[]) delta.get("dv"));
        assertFalse(delta.containsKey("v"));
        assertFalse(delta.containsKey("f"));
        assertFalse(delta.containsKey("c"));

        // Different topology (vertex count): written in full.
        Map<String, Object> full = firstOfType(
            sampler.frame(3, List.of(posed("A", 3, () -> geometry(4, 0))), List.of()), "model");
        assertFalse(full.containsKey("base"));
        assertEquals(12, ((int[]) full.get("v")).length);

        // A new appearance gets its own full base.
        sampler.tick(4, 2, List.of(look("A", 1)), List.of());
        Map<String, Object> other = firstOfType(
            sampler.frame(4, List.of(posed("A", 2, () -> geometry(3, 4))), List.of()), "model");
        assertFalse(other.containsKey("base"));
    }

    @Test
    public void noModelUntilAppearanceKnownOrWhileSpotAnimActive()
    {
        ReplaySampler sampler = new ReplaySampler();
        AtomicInteger calls = new AtomicInteger();
        List<Map<String, Object>> lines = sampler.frame(1, List.of(posed("A", 1, counting(calls, geometry(3, 0)))),
            List.of());
        assertFalse(types(lines).contains("pm"));
        assertEquals(0, calls.get());

        sampler.tick(1, 1, List.of(look("A", 0)), List.of());
        // A spot anim is merged into the client's player model: don't pin it to the key.
        PlayerState withSpot = new PlayerState("A", 100, 200, 0, 1, 0, 808, 0, new int[] { 85, 0, 0 },
            counting(calls, geometry(3, 0)), null);
        assertFalse(types(sampler.frame(2, List.of(withSpot), List.of())).contains("pm"));
        assertEquals(0, calls.get());

        assertTrue(types(sampler.frame(3, List.of(posed("A", 1, counting(calls, geometry(3, 0)))), List.of()))
            .contains("pm"));
        assertEquals(1, calls.get());
    }

    private static PlayerState spotted(String name, int[] spots, Supplier<ModelCapture.Geometry> model)
    {
        return new PlayerState(name, 100, 200, 0, 1, 0, 808, 0, spots, model, null);
    }

    @Test
    public void spawnedWithSpotAnimGetsPmAfterTheCap()
    {
        ReplaySampler sampler = new ReplaySampler();
        AtomicInteger calls = new AtomicInteger();
        sampler.tick(1, 1, List.of(look("A", 0)), List.of());
        for (int c = 1; c <= ReplaySampler.SPOT_DEFER_CAP; c++)
        {
            assertFalse("cycle " + c, types(sampler.frame(c, List.of(spotted("A", new int[] { 90, c, 0, 85, c, 0 },
                counting(calls, geometry(4, 0)))), List.of())).contains("pm"));
        }
        assertEquals(0, calls.get());

        // Past the cap: captured anyway, under a key carrying the sorted spot ids.
        List<Map<String, Object>> capped = sampler.frame(ReplaySampler.SPOT_DEFER_CAP + 1, List.of(spotted("A",
            new int[] { 90, 0, 0, 85, 0, 0 }, counting(calls, geometry(4, 0)))), List.of());
        assertArrayEquals(new int[] { 0, 0 }, pmRows(capped).get(0));
        assertEquals(1, calls.get());

        // The spot anim ends: the clean pose key is captured on its own, not taken from the merged model.
        List<Map<String, Object>> clean = sampler.frame(ReplaySampler.SPOT_DEFER_CAP + 2,
            List.of(posed("A", 1, counting(calls, geometry(3, 0)))), List.of());
        assertArrayEquals(new int[] { 0, 1 }, pmRows(clean).get(0));
        assertEquals(2, calls.get());
        assertEquals(9, ((int[]) firstOfType(clean, "model").get("v")).length);

        // The counter reset when the spot anim cleared: a new spot anim defers again.
        assertFalse(types(sampler.frame(ReplaySampler.SPOT_DEFER_CAP + 3, List.of(new PlayerState("A", 100, 200, 0, 2,
            0, 808, 0, new int[] { 85, 0, 0 }, counting(calls, geometry(4, 0)), null)), List.of())).contains("pm"));
        assertEquals(2, calls.get());
    }

    @Test
    public void noCaptureBetweenAnAppearanceChangeAndTheNextTick()
    {
        ReplaySampler sampler = new ReplaySampler();
        AtomicInteger calls = new AtomicInteger();
        sampler.tick(1, 1, List.of(look("A", 0)), List.of());
        int newLook = look("A", 1).hash();

        // The composition already changed (new look), the tick hash is still the old one: wait.
        PlayerState changed = new PlayerState("A", 100, 200, 0, 1, 0, 808, 0, PlayerState.NO_SPOTS,
            counting(calls, geometry(3, 0)), newLook);
        assertFalse(types(sampler.frame(1, List.of(changed), List.of())).contains("pm"));
        assertEquals(0, calls.get());

        // The next tick re-hashes: captured under the new look's key.
        sampler.tick(2, 2, List.of(look("A", 1)), List.of());
        assertTrue(types(sampler.frame(2, List.of(changed), List.of())).contains("pm"));
        assertEquals(1, calls.get());
        // Filed under the new look's key: the same frame again is a known key, so no new capture.
        sampler.frame(3, List.of(changed), List.of());
        assertEquals(1, calls.get());

        // An unknown frame look (no composition) never blocks capture.
        PlayerState unknown = new PlayerState("A", 100, 200, 0, 2, 0, 808, 0, PlayerState.NO_SPOTS,
            counting(calls, geometry(3, 0)), null);
        assertTrue(types(sampler.frame(3, List.of(unknown), List.of())).contains("pm"));
    }

    @Test
    public void respawnWritesPmAgain()
    {
        ReplaySampler sampler = new ReplaySampler();
        sampler.tick(1, 1, List.of(look("A", 0)), List.of());
        sampler.frame(1, List.of(posed("A", 1, () -> geometry(3, 0))), List.of());
        sampler.frame(2, List.of(), List.of());
        List<Map<String, Object>> back = sampler.frame(3, List.of(posed("A", 1, () -> geometry(3, 0))), List.of());
        assertArrayEquals(new int[] { 0, 0 }, pmRows(back).get(0));
    }

    @Test
    public void modelPassCostWithNoNewKeys()
    {
        // 12 players, every key already known: per player, the recorder's frame-time look hash
        // (Appearance.hash over the composition arrays), then a key build and a lookup.
        ReplaySampler sampler = new ReplaySampler();
        List<Appearance> looks = new ArrayList<>();
        for (int i = 0; i < 12; i++)
        {
            looks.add(look("P" + i, i));
        }
        sampler.tick(1, 1, looks, List.of());
        long total = 0;
        long worst = 0;
        int warm = 20_000;
        int n = 20_000;
        for (int c = 0; c < warm + n; c++)
        {
            long t0 = System.nanoTime();
            List<PlayerState> players = new ArrayList<>(12);
            for (Appearance a : looks)
            {
                players.add(new PlayerState(a.name, 100, 200, 0, 1, 0, 808, 0, PlayerState.NO_SPOTS,
                    () -> geometry(3, 0), Appearance.hash(a.gender, a.equipment, a.colors)));
            }
            long lookNanos = System.nanoTime() - t0;
            sampler.frame(c, players, List.of());
            if (c >= warm)
            {
                long spent = lookNanos + sampler.lastModelNanos();
                total += spent;
                worst = Math.max(worst, spent);
            }
        }
        double avgMicros = total / (double) n / 1000.0;
        System.out.printf("model pass, 12 players, no new keys: avg %.2f us, worst %.1f us%n", avgMicros,
            worst / 1000.0);
        assertTrue("avg " + avgMicros + " us", avgMicros < 50.0);
    }

    // ---- P6: spread-out house capture and skip reasons ----

    // ---- Pitch window: the whole house, not the tiles around the recorder ----

}
