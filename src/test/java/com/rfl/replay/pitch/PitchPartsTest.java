package com.rfl.replay.pitch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.rfl.replay.ReplaySampler;
import sh.yumekui.toolkit.model.ModelCapture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.Test;

/**
 * The pitch line's parts on their own: object rows ({@link PitchObjects}), floor crops and paint
 * ({@link PitchFloor}), the scan window ({@link PitchWindow}), loc keys and rows ({@link Loc}), and
 * the spread-out house model capture ({@link LocPass}): its time budget, skip reasons and loc names.
 * {@link ReplaySampler} supplies the model ids, as it does in a recording.
 */
public class PitchPartsTest
{
    /** Local coordinate of scene tile {@code index}'s centre: what the fixtures' 6464-style numbers are. */
    private static int tileCentre(int index)
    {
        return PitchObjects.tileCentre(index);
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

    /** A loc capture that advances {@code clock} by {@code nanos}, as if reading the model took that long. */
    private static Supplier<LocModel> slow(AtomicLong clock, long nanos, ModelCapture.Geometry g)
    {
        return () ->
        {
            clock.addAndGet(nanos);
            return LocModel.of(g);
        };
    }

    /** Template chunks like a real house: a 9x9 block at chunks (2..10, 2..10) on planes 0 and 1. */
    private static int[][][] houseChunks()
    {
        int[][][] chunks = new int[4][13][13];
        for (int[][] plane : chunks)
        {
            for (int[] col : plane)
            {
                java.util.Arrays.fill(col, PitchWindow.NO_CHUNK);
            }
        }
        for (int p = 0; p < 2; p++)
        {
            for (int x = 2; x <= 10; x++)
            {
                for (int y = 2; y <= 10; y++)
                {
                    chunks[p][x][y] = 54270888 + x + y;
                }
            }
        }
        return chunks;
    }

    /** A loc whose capture yields bare geometry (null: no model); a null supplier means no renderable. */
    private static Loc loc(int id, int config, int part, int x, int y, int height,
        Supplier<ModelCapture.Geometry> model)
    {
        return Loc.withReasons(id, config, part, 0, x, y, height, model == null ? null : () -> LocModel.of(model.get()));
    }

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

    @Test
    public void pitchObjectsDedupeByTypeAndHash()
    {
        PitchObjects objs = new PitchObjects();
        assertTrue(objs.add(PitchObjects.GAME, 77L, 100, 512, 6400, 6400, 0, 6400, 6400, 1, 1));
        // Same multi-tile GameObject seen again from a second tile.
        assertFalse(objs.add(PitchObjects.GAME, 77L, 100, 512, 6400, 6400, 0, 6400, 6400, 1, 1));
        // Same hash on a different layer is a different object.
        assertTrue(objs.add(PitchObjects.WALL, 77L, 200, 1, 6528, 6400, 0, 6528, 6400, 1, 1));

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
        // Literal centres, so this checks the formula: tile 50 is 50 * 128 + 64, tile 60 is 60 * 128 + 64.
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
    public void pitchCarriesLocsAndPaint()
    {
        ReplaySampler sampler = new ReplaySampler();
        AtomicInteger calls = new AtomicInteger();
        // config: shape 10, rotation 1.
        int config = 10 | (1 << 6);
        LocPass pass = new LocPass(sampler, List.of(
            loc(4000, config, 0, tileCentre(50), tileCentre(50), -10, counting(calls, geometry(3, 0))),
            loc(4000, config, 0, tileCentre(51), tileCentre(50), -12, counting(calls, geometry(3, 7))),
            loc(4000, 10, 0, tileCentre(52), tileCentre(50), 0, counting(calls, geometry(3, 1))),
            loc(4001, 0, 0, tileCentre(53), tileCentre(50), 0, () -> null),
            loc(4002, 0, 0, tileCentre(54), tileCentre(50), 0, null)), new LocNames(null));
        // An unlimited budget reads every loc in one step.
        assertTrue(pass.step(Long.MAX_VALUE, System::nanoTime));
        List<Map<String, Object>> modelLines = sampler.newModelLines();

        assertEquals(2, calls.get());
        assertEquals(2, modelLines.size());
        assertEquals("loc", modelLines.get(0).get("kind"));
        assertEquals(3, pass.rows().size());
        assertArrayEquals(new Object[] { 0, tileCentre(50), tileCentre(50), -10, 4000, "o" }, pass.rows().get(0));
        assertArrayEquals(new Object[] { 0, tileCentre(51), tileCentre(50), -12, 4000, "o" }, pass.rows().get(1));
        assertArrayEquals(new Object[] { 1, tileCentre(52), tileCentre(50), 0, 4000, "o" }, pass.rows().get(2));
        assertEquals(2, pass.skipped());

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
    public void locPassSpreadsCaptureOverStepsWithinTheBudget()
    {
        ReplaySampler sampler = new ReplaySampler();
        AtomicLong clock = new AtomicLong();
        List<Loc> locs = new ArrayList<>();
        for (int k = 0; k < 5; k++)
        {
            // Distinct ids, so each loc is a new key and costs a capture of 400 us.
            locs.add(Loc.withReasons(5000 + k, 10, 0, 0, tileCentre(50) + k * 128, tileCentre(50), -k, slow(clock, 400_000L, geometry(3, k))));
        }
        LocPass pass = new LocPass(sampler, locs, new LocNames(null));

        // 1 ms budget: 400, 800, then 1200 us >= 1 ms stops the step after the third loc.
        assertFalse(pass.step(1_000_000L, clock::get));
        assertEquals(3, pass.read());
        assertEquals(3, pass.rows().size());
        List<Map<String, Object>> first = sampler.newModelLines();
        assertEquals(List.of("model", "model", "model"), types(first));
        assertEquals("loc", first.get(0).get("kind"));

        assertTrue(pass.step(1_000_000L, clock::get));
        assertTrue(pass.done());
        assertEquals(5, pass.rows().size());
        assertEquals(2, sampler.newModelLines().size());
        assertArrayEquals(new Object[] { 4, tileCentre(50) + 4 * 128, tileCentre(50), -4, 5004, "o" }, pass.rows().get(4));
        assertEquals(0, pass.skipped());
    }

    @Test
    public void locPassReadsAtLeastOneLocPerStepEvenOverBudget()
    {
        ReplaySampler sampler = new ReplaySampler();
        AtomicLong clock = new AtomicLong();
        LocPass pass = new LocPass(sampler, List.of(
            Loc.withReasons(1, 10, 0, 0, 0, 0, 0, slow(clock, 5_000_000L, geometry(3, 0))),
            Loc.withReasons(2, 10, 0, 0, 0, 0, 0, slow(clock, 5_000_000L, geometry(3, 1)))), new LocNames(null));

        assertFalse(pass.step(1_000_000L, clock::get));
        assertEquals(1, pass.read());
        assertTrue(pass.step(1_000_000L, clock::get));
    }

    @Test
    public void knownKeysCostNoCaptureAndDoNotEndTheStep()
    {
        ReplaySampler sampler = new ReplaySampler();
        AtomicLong clock = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        Supplier<LocModel> capture = () ->
        {
            calls.incrementAndGet();
            clock.addAndGet(100_000L);
            return LocModel.of(geometry(3, 0));
        };
        List<Loc> locs = new ArrayList<>();
        for (int k = 0; k < 50; k++)
        {
            locs.add(Loc.withReasons(7000, 0, 0, 0, k * 128, 0, 0, capture));
        }
        LocPass pass = new LocPass(sampler, locs, new LocNames(null));

        assertTrue("one capture, 49 lookups, all inside one budget", pass.step(1_000_000L, clock::get));
        assertEquals(1, calls.get());
        assertEquals(50, pass.rows().size());
    }

    @Test
    public void locPassCountsEachSkipReason()
    {
        ReplaySampler sampler = new ReplaySampler();
        AtomicInteger failedCalls = new AtomicInteger();
        Supplier<LocModel> noModel = () ->
        {
            failedCalls.incrementAndGet();
            return LocModel.skipped(LocSkip.NO_MODEL);
        };
        LocPass pass = new LocPass(sampler, List.of(
            Loc.withReasons(1, 0, 0, 0, 0, 0, 0, null),
            Loc.withReasons(2, 0, 0, 0, 0, 0, 0, noModel),
            // Same key as the one above: not read again.
            Loc.withReasons(2, 0, 0, 0, 128, 0, 0, noModel),
            Loc.withReasons(3, 0, 0, 0, 0, 0, 0, () -> LocModel.skipped(LocSkip.NO_ARRAYS)),
            Loc.withReasons(4, 0, 0, 0, 0, 0, 0, () -> LocModel.skipped(LocSkip.THREW)),
            Loc.withReasons(5, 0, 0, 0, 0, 0, 0, () -> null),
            Loc.withReasons(6, 0, 0, 0, 0, 0, 0, () -> LocModel.of(geometry(3, 0))),
            Loc.withReasons(7, 0, 0, 0, 0, 0, 0, () -> LocModel.of(geometry(3, 1))),
            Loc.withReasons(8, 0, 0, 0, 0, 0, 0, () -> LocModel.of(geometry(3, 2)))), new LocNames(null));

        // Stop after the 7th loc, then give up on the rest.
        AtomicLong clock = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        pass.step(Long.MAX_VALUE, () -> reads.incrementAndGet() > 7 ? Long.MAX_VALUE : 0L);
        assertEquals(7, pass.read());
        pass.abandon();
        assertTrue(pass.done());

        int[] skips = pass.skips();
        assertEquals(1, skips[LocSkip.NO_RENDERABLE.ordinal()]);
        assertEquals("a null result counts as no model", 2, skips[LocSkip.NO_MODEL.ordinal()]);
        assertEquals(1, skips[LocSkip.SAME_KEY_FAILED.ordinal()]);
        assertEquals(1, skips[LocSkip.NO_ARRAYS.ordinal()]);
        assertEquals(1, skips[LocSkip.THREW.ordinal()]);
        assertEquals(2, skips[LocSkip.UNFINISHED.ordinal()]);
        assertEquals(8, pass.skipped());
        assertEquals(1, pass.rows().size());
        assertEquals(1, failedCalls.get());
    }

    @Test
    public void locKeyAddsPartAndNonZeroOrientation()
    {
        int config = 10 | (2 << 6);
        assertEquals("l:9:10:2", Loc.withReasons(9, config, 0, 0, 0, 0, 0, null).key());
        assertEquals("l:9:10:2:1", Loc.withReasons(9, config, 1, 0, 0, 0, 0, null).key());
        assertEquals("l:9:10:2:o256", Loc.withReasons(9, config, 0, 256, 0, 0, 0, null).key());
    }

    @Test
    public void offCentreRecorderStillGetsBothHouseEdges()
    {
        // Recorder near the west/north part of the house, as in 2026-10-03_112831 (walls at x=31
        // were in, the east wall line past x=71 was not).
        int cx = 51;
        int cy = 52;
        PitchWindow old = PitchWindow.around(cx, cy, 20);
        assertFalse("the old radius window misses the east edge", old.contains(87, 52));

        PitchWindow house = PitchWindow.house(houseChunks(), cx, cy, 20);
        // Chunks 2..10 are tiles 16..87; one tile of margin each side.
        assertEquals(15, house.x0);
        assertEquals(15, house.y0);
        assertEquals(88, house.x1);
        assertEquals(88, house.y1);
        for (int edge : new int[] { 16, 87 })
        {
            assertTrue(house.contains(edge, 50));
            assertTrue(house.contains(50, edge));
        }

        // Crops and paint keep both edges too.
        short[][] under = new short[104][104];
        under[16][40] = 5;
        under[87][40] = 6;
        int[][] cropped = PitchFloor.crop(under, house);
        assertEquals(5, cropped[16][40]);
        assertEquals(6, cropped[87][40]);
        int[][] rgb = new int[104][104];
        rgb[87][87] = 0x010203;
        int[] paint = PitchFloor.paint(rgb, house);
        int at = (87 * 104 + 87) * 3;
        assertArrayEquals(new int[] { 1, 2, 3 }, new int[] { paint[at], paint[at + 1], paint[at + 2] });
        assertEquals("outside the house stays 0", 0, PitchFloor.crop(under, house)[90][40]);
    }

    @Test
    public void windowFallsBackToTheRadiusOutsideAnInstance()
    {
        PitchWindow w = PitchWindow.house(null, 50, 50, 20);
        assertEquals(30, w.x0);
        assertEquals(70, w.x1);
        int[][][] empty = houseChunks();
        for (int[][] plane : empty)
        {
            for (int[] col : plane)
            {
                java.util.Arrays.fill(col, PitchWindow.NO_CHUNK);
            }
        }
        assertEquals(30, PitchWindow.house(empty, 50, 50, 20).x0);
        // Unknown recorder tile and no instance: nothing.
        PitchWindow none = PitchWindow.around(-21, -21, 20);
        assertTrue(none.x0 > none.x1);
        // A house touching the scene edge is clamped.
        int[][][] edge = empty;
        edge[0][0][12] = 7;
        PitchWindow clamped = PitchWindow.house(edge, 0, 0, 20);
        assertEquals(0, clamped.x0);
        assertEquals(103, clamped.y1);
    }

    @Test
    public void locPassReportsItsSlowestRead()
    {
        ReplaySampler sampler = new ReplaySampler();
        AtomicLong clock = new AtomicLong();
        LocPass pass = new LocPass(sampler, List.of(
            Loc.withReasons(1, 10, 0, 0, 0, 0, 0, slow(clock, 300_000L, geometry(3, 0))),
            Loc.withReasons(2, 10, 0, 0, 0, 0, 0, slow(clock, 700_000L, geometry(3, 1)))), new LocNames(null));
        pass.step(10_000_000L, clock::get);
        assertEquals(700_000L, pass.worstReadNanos());
    }

    @Test
    public void locRowsCarryTheLocIdAndKind()
    {
        ReplaySampler sampler = new ReplaySampler();
        LocPass pass = new LocPass(sampler, List.of(
            Loc.withReasons(10, Loc.GROUND, 22, 0, 0, 100, 200, -3, () -> LocModel.of(geometry(3, 0))),
            Loc.withReasons(11, Loc.DECORATIVE, 4, 0, 0, 110, 200, 0, () -> LocModel.of(geometry(3, 1))),
            Loc.withReasons(12, Loc.WALL, 0, 0, 0, 120, 200, 0, () -> LocModel.of(geometry(3, 2))),
            Loc.withReasons(13, Loc.GAME, 10, 0, 0, 130, 200, 0, () -> LocModel.of(geometry(3, 3)))), new LocNames(null));
        pass.step(Long.MAX_VALUE, System::nanoTime);
        List<String> kinds = new ArrayList<>();
        for (Object[] row : pass.rows())
        {
            assertEquals("[modelId, localX, localY, groundHeight, locId, kind]", 6, row.length);
            kinds.add((String) row[5]);
        }
        assertEquals(List.of("g", "d", "w", "o"), kinds);
        assertArrayEquals(new Object[] { 0, 100, 200, -3, 10, "g" }, pass.rows().get(0));
        assertEquals("[[0,100,200,-3,10,\"g\"]]", new com.google.gson.GsonBuilder().create()
            .toJson(pass.rows().subList(0, 1)));
    }

    @Test
    public void locNamesGoOutOnlyWhereALocIdFirstAppears()
    {
        ReplaySampler sampler = new ReplaySampler();
        List<Integer> lookups = new ArrayList<>();
        // One file's names: each id is looked up and named once, across passes.
        LocNames fileNames = new LocNames(id ->
        {
            lookups.add(id);
            return id == 20 ? "Grass" : id == 21 ? null : "Plant";
        });
        LocPass first = new LocPass(sampler, List.of(
            Loc.withReasons(20, Loc.GROUND, 22, 0, 0, 100, 200, 0, () -> LocModel.of(geometry(3, 0))),
            Loc.withReasons(20, Loc.GROUND, 22, 0, 0, 228, 200, 0, () -> LocModel.of(geometry(3, 0))),
            Loc.withReasons(21, Loc.GAME, 10, 0, 0, 356, 200, 0, () -> LocModel.of(geometry(3, 1)))), fileNames);
        first.step(Long.MAX_VALUE, System::nanoTime);
        Map<String, String> names = first.takeNames();
        assertEquals(Map.of("20", "Grass", "21", ""), names);
        assertTrue("taken once", first.takeNames().isEmpty());

        // A later pass in the same file (a reload) names only ids it introduces.
        LocPass second = new LocPass(sampler, List.of(
            Loc.withReasons(20, Loc.GROUND, 22, 0, 0, 100, 200, 0, () -> LocModel.of(geometry(3, 0))),
            Loc.withReasons(22, Loc.WALL, 0, 0, 0, 100, 328, 0, () -> LocModel.of(geometry(3, 2)))), fileNames);
        second.step(Long.MAX_VALUE, System::nanoTime);
        assertEquals(Map.of("22", "Plant"), second.takeNames());
        assertEquals("one lookup per id", List.of(20, 21, 22), lookups);
    }
}
