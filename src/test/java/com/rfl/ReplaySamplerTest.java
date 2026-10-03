package com.rfl;

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

import com.rfl.ReplaySampler.Appearance;
import com.rfl.ReplaySampler.Loc;
import com.rfl.ReplaySampler.Ball;
import com.rfl.ReplaySampler.PitchFloor;
import com.rfl.ReplaySampler.PitchObjects;
import com.rfl.ReplaySampler.PlayerState;
import com.rfl.ReplaySampler.TrueTile;

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
    public void noBallLinesBeforeTheProjectileStarts()
    {
        // The client creates the projectile ~0.8 s early, parked at (0, 0) through its start cycle;
        // the first real position is the cycle after (seen in a real recording).
        ReplaySampler sampler = new ReplaySampler();
        Ball parked = new Ball(1528, 100, 0.0, 0.0, 0.0, 0);
        assertTrue(sampler.frame(99, List.of(), List.of(parked)).isEmpty());
        assertTrue(sampler.frame(100, List.of(), List.of(parked)).isEmpty());
        assertEquals(1, sampler.frame(101, List.of(), List.of(new Ball(1528, 100, 8896, 5696, -651, 1175))).size());
    }

    @Test
    public void ballLinesEveryCycleWhileInFlight()
    {
        ReplaySampler sampler = new ReplaySampler();
        // Started at cycle 0, so it is in flight (has a position) from cycle 1.
        Ball ball = new Ball(7, 0, 1.5, 2.5, 0.0, 90);

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

    @Test
    public void appearanceWaitsForSpawn()
    {
        ReplaySampler sampler = new ReplaySampler();
        int[] eq = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };
        int[] col = { 1, 2, 3, 4, 5 };
        Appearance a = new Appearance("A", 0, eq, col);

        List<Map<String, Object>> beforeSpawn = sampler.tick(1, 100, List.of(a));
        assertEquals(1, beforeSpawn.size());
        assertEquals("tick", beforeSpawn.get(0).get("t"));
        assertEquals(-1, sampler.indexOf("A"));

        List<Map<String, Object>> spawnFrame = sampler.frame(1, List.of(player("A", 100, 200, 0, 1, 2, 3, 4)),
            List.of());
        assertEquals(0, firstOfType(spawnFrame, "spawn").get("i"));

        List<Map<String, Object>> afterSpawn = sampler.tick(2, 101, List.of(a));
        assertEquals(2, afterSpawn.size());
        assertEquals("app", afterSpawn.get(1).get("t"));
        assertEquals(0, afterSpawn.get(1).get("i"));

        sampler.frame(2, List.of(), List.of());
        sampler.frame(3, List.of(player("A", 100, 200, 0, 1, 2, 3, 4)), List.of());

        List<Map<String, Object>> afterRespawn = sampler.tick(3, 102, List.of(a));
        assertEquals(2, afterRespawn.size());
        assertEquals("app", afterRespawn.get(1).get("t"));
    }

    private static PlayerState spotted(String name, int... spots)
    {
        return new PlayerState(name, 100, 200, 0, 1, 2, 3, 4, spots);
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
    public void pitchObjectsBoxIsChebyshevClippedToScene()
    {
        assertEquals(0, PitchObjects.lo(10, 20));
        assertEquals(32, PitchObjects.lo(52, 20));
        assertEquals(103, PitchObjects.hi(100, 20, 104));
        assertEquals(72, PitchObjects.hi(52, 20, 104));
    }

    @Test
    public void pitchObjectsDedupeByTypeAndHash()
    {
        PitchObjects objs = new PitchObjects();
        assertTrue(objs.add(PitchObjects.GAME, 77L, 100, 512, 6400, 6400));
        // Same multi-tile GameObject seen again from a second tile.
        assertFalse(objs.add(PitchObjects.GAME, 77L, 100, 512, 6400, 6400));
        // Same hash on a different layer is a different object.
        assertTrue(objs.add(PitchObjects.WALL, 77L, 200, 1, 6528, 6400));

        List<int[]> rows = objs.rows();
        assertEquals(2, rows.size());
        assertArrayEquals(new int[] { 100, 0, 512, 6400, 6400 }, rows.get(0));
        assertArrayEquals(new int[] { 200, 1, 1, 6528, 6400 }, rows.get(1));
        // The legacy add fills objs2 with config 0 and a 1x1 footprint at the same point.
        assertArrayEquals(new int[] { 100, 0, 0, 6400, 6400, 1, 1 }, objs.rows2().get(0));
    }

    @Test
    public void pitchObjectConfigDecodesShapeAndRotation()
    {
        // Shape 10 (game object), rotation 3, plus unrelated high bits.
        int config = 10 | (3 << 6) | (1 << 8);
        assertEquals(10, PitchObjects.shape(config));
        assertEquals(3, PitchObjects.rotation(config));
        // Roof shape 21, rotation 1.
        assertEquals(21, PitchObjects.shape(21 | (1 << 6)));
        assertEquals(1, PitchObjects.rotation(21 | (1 << 6)));
        // Bit 5 is neither shape nor rotation.
        assertEquals(0, PitchObjects.shape(32));
        assertEquals(0, PitchObjects.rotation(32));
    }

    @Test
    public void pitchObjects2PlaceGameObjectAtSouthWestTileCentre()
    {
        PitchObjects objs = new PitchObjects();
        // A 2x2 GameObject spanning scene tiles (50,60)..(51,61): getLocalLocation is its centre
        // (51*128, 61*128), the south-west tile centre is (50*128+64, 60*128+64).
        int minX = 50;
        int minY = 60;
        int maxX = 51;
        int maxY = 61;
        int config = 10 | (2 << 6);
        assertTrue(objs.add(PitchObjects.GAME, 9L, 4321, 1024, 6528, 7808, config,
            PitchObjects.tileCentre(minX), PitchObjects.tileCentre(minY),
            PitchObjects.span(minX, maxX), PitchObjects.span(minY, maxY)));
        // Offered again from another of its four tiles: still listed once in both arrays.
        assertFalse(objs.add(PitchObjects.GAME, 9L, 4321, 1024, 6528, 7808, config,
            PitchObjects.tileCentre(minX), PitchObjects.tileCentre(minY), 2, 2));

        assertEquals(1, objs.rows().size());
        assertEquals(1, objs.rows2().size());
        assertArrayEquals(new int[] { 4321, 0, 1024, 6528, 7808 }, objs.rows().get(0));
        assertArrayEquals(new int[] { 4321, 0, config, 6464, 7744, 2, 2 }, objs.rows2().get(0));
    }

    @Test
    public void pitchFloorCropsToRadiusAndZeroesOutside()
    {
        short[][] under = new short[104][104];
        byte[][] shapes = new byte[104][104];
        for (short[] col : under)
        {
            java.util.Arrays.fill(col, (short) 7);
        }
        for (byte[] col : shapes)
        {
            java.util.Arrays.fill(col, (byte) 200);
        }

        int[][] u = PitchFloor.crop(under, 52, 52, 20);
        int[][] s = PitchFloor.crop(shapes, 52, 52, 20);

        assertEquals(104, u.length);
        assertEquals(104, u[0].length);
        assertEquals(7, u[32][32]);
        assertEquals(7, u[72][72]);
        assertEquals(0, u[31][52]);
        assertEquals(0, u[52][73]);
        assertEquals(0, u[0][0]);
        // Bytes are unsigned.
        assertEquals(200, s[52][52]);
        assertEquals(0, s[73][52]);
        // Off-scene centre (recorder tile unknown): everything is 0.
        assertEquals(0, PitchFloor.crop(under, -21, -21, 20)[0][0]);
        // Null plane: all zero, still 104x104.
        assertEquals(104, PitchFloor.crop((short[][]) null, 52, 52, 20).length);
    }

    @Test
    public void pitchFloorReadsExtendedSceneAtOffset()
    {
        short[][] over = new short[184][184];
        // Scene tile (10, 12) sits at extended index (50, 52).
        over[50][52] = 33;
        over[10][12] = 99;

        int[][] o = PitchFloor.crop(over, 10, 12, 20);

        assertEquals(40, PitchFloor.offset(184));
        assertEquals(0, PitchFloor.offset(105));
        assertEquals(33, o[10][12]);
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
        return new PlayerState(name, 100, 200, 0, anim, 0, 808, 0, ReplaySampler.NO_SPOTS, model);
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
        sampler.tick(1, 1, List.of(look("A", 0)));

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
        sampler.tick(1, 1, List.of(look("A", 0), look("B", 0)));

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
        Ball bare = new Ball(1529, 10, 1, 2, 3, 0);
        assertFalse(sampler.frame(13, List.of(), List.of(bare)).get(0).containsKey("m"));
    }

    @Test
    public void pitchCarriesLocsAndPaint()
    {
        ReplaySampler sampler = new ReplaySampler();
        AtomicInteger calls = new AtomicInteger();
        // config: shape 10, rotation 1.
        int config = 10 | (1 << 6);
        ReplaySampler.PitchLocs locs = sampler.pitchLocs(List.of(
            new Loc(4000, config, 0, 6464, 6464, -10, counting(calls, geometry(3, 0))),
            new Loc(4000, config, 0, 6592, 6464, -12, counting(calls, geometry(3, 7))),
            new Loc(4000, 10, 0, 6720, 6464, 0, counting(calls, geometry(3, 1))),
            new Loc(4001, 0, 0, 6848, 6464, 0, () -> null),
            new Loc(4002, 0, 0, 6976, 6464, 0, null)));

        assertEquals(2, calls.get());
        assertEquals(2, locs.lines().size());
        assertEquals("loc", locs.lines().get(0).get("kind"));
        assertEquals(3, locs.rows().size());
        assertArrayEquals(new int[] { 0, 6464, 6464, -10 }, locs.rows().get(0));
        assertArrayEquals(new int[] { 0, 6592, 6464, -12 }, locs.rows().get(1));
        assertArrayEquals(new int[] { 1, 6720, 6464, 0 }, locs.rows().get(2));
        assertEquals(2, locs.skipped());

        int[][] rgb = new int[104][104];
        rgb[50][50] = 0x112233;
        rgb[90][90] = 0x445566;
        int[] paint = PitchFloor.paint(rgb, 50, 50, 20);
        assertEquals(104 * 104 * 3, paint.length);
        int at = (50 * 104 + 50) * 3;
        assertArrayEquals(new int[] { 0x11, 0x22, 0x33 },
            new int[] { paint[at], paint[at + 1], paint[at + 2] });
        int out = (90 * 104 + 90) * 3;
        assertEquals("outside the radius is cropped", 0, paint[out] + paint[out + 1] + paint[out + 2]);
    }

    @Test
    public void laterPoseOfAnAppearanceIsDeltaEncoded()
    {
        ReplaySampler sampler = new ReplaySampler();
        sampler.tick(1, 1, List.of(look("A", 0)));
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
        sampler.tick(4, 2, List.of(look("A", 1)));
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

        sampler.tick(1, 1, List.of(look("A", 0)));
        // A spot anim is merged into the client's player model: don't pin it to the key.
        PlayerState withSpot = new PlayerState("A", 100, 200, 0, 1, 0, 808, 0, new int[] { 85, 0, 0 },
            counting(calls, geometry(3, 0)));
        assertFalse(types(sampler.frame(2, List.of(withSpot), List.of())).contains("pm"));
        assertEquals(0, calls.get());

        assertTrue(types(sampler.frame(3, List.of(posed("A", 1, counting(calls, geometry(3, 0)))), List.of()))
            .contains("pm"));
        assertEquals(1, calls.get());
    }

    @Test
    public void respawnWritesPmAgain()
    {
        ReplaySampler sampler = new ReplaySampler();
        sampler.tick(1, 1, List.of(look("A", 0)));
        sampler.frame(1, List.of(posed("A", 1, () -> geometry(3, 0))), List.of());
        sampler.frame(2, List.of(), List.of());
        List<Map<String, Object>> back = sampler.frame(3, List.of(posed("A", 1, () -> geometry(3, 0))), List.of());
        assertArrayEquals(new int[] { 0, 0 }, pmRows(back).get(0));
    }

    @Test
    public void modelPassCostWithNoNewKeys()
    {
        // 12 players, every key already known: only a key build and a lookup per player.
        ReplaySampler sampler = new ReplaySampler();
        List<Appearance> looks = new ArrayList<>();
        List<PlayerState> players = new ArrayList<>();
        for (int i = 0; i < 12; i++)
        {
            looks.add(look("P" + i, i));
            players.add(posed("P" + i, 1, () -> geometry(3, 0)));
        }
        sampler.tick(1, 1, looks);
        for (int c = 0; c < 20_000; c++)
        {
            sampler.frame(c, players, List.of());
        }
        long total = 0;
        long worst = 0;
        int n = 20_000;
        for (int c = 0; c < n; c++)
        {
            sampler.frame(20_000 + c, players, List.of());
            total += sampler.lastModelNanos();
            worst = Math.max(worst, sampler.lastModelNanos());
        }
        double avgMicros = total / (double) n / 1000.0;
        System.out.printf("model pass, 12 players, no new keys: avg %.2f us, worst %.1f us%n", avgMicros,
            worst / 1000.0);
        assertTrue("avg " + avgMicros + " us", avgMicros < 50.0);
    }
}
