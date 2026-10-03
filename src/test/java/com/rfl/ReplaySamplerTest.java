package com.rfl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.rfl.ReplaySampler.Appearance;
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
}
