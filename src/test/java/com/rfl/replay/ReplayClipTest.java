package com.rfl.replay;

import com.rfl.RflConfig;
import com.rfl.contact.Collision;
import com.rfl.log.PluginEntry;
import com.rfl.teams.Teams;
import sh.yumekui.toolkit.io.GzipNdjsonWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldView;

/**
 * The replay buffer clip ({@link ReplayRing}, {@link ClipBuilder}, {@link ClipWriter}): a clip of a
 * synthetic recording holds exactly the window's lines plus a valid start state, reads back under
 * the replay website's own line rules ({@link WebsiteRules}), follows pitch reloads, shortens
 * under the byte cap, and costs the caller one queued step.
 */
public class ReplayClipTest
{
    private static final Gson GSON = new GsonBuilder().create();
    /** The synthetic recording: cycles 1000 to 2000; a 500-cycle (10 s) clip is 1500 to 2000. */
    private static final int START = 1000;
    private static final int END = 2000;
    private static final int CLIP = 500;
    /** The ring keeps the 10 s clip plus 2 s, so the recording's start has left it by the end. */
    private static final int KEEP_SECONDS = 12;

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    // ---- The synthetic recording ----

    /** Where a synthetic recording's pitch reload goes, if any. */
    private enum Reload
    {
        NONE,
        BEFORE_WINDOW,
        INSIDE_WINDOW
    }

    private static final int RELOAD_BEFORE = 1200;
    private static final int RELOAD_INSIDE = 1700;

    private static Map<String, Object> model(int id, String kind, Integer base)
    {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", "model");
        line.put("id", id);
        line.put("kind", kind);
        if (base != null)
        {
            line.put("base", base);
            line.put("dv", new int[] { 1, 0, -1, 2, 0, 0, 0, 1, 0 });
        }
        else
        {
            line.put("v", new int[] { id, 0, 0, 0, id, 0, 0, 0, id });
            line.put("f", new int[] { 0, 1, 2 });
            line.put("c", new int[] { 9, 9, 9 });
        }
        return line;
    }

    private static Map<String, Object> pitch(int cyc, int baseX, int locModel, int locId)
    {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", "pitch");
        line.put("cyc", cyc);
        line.put("plane", 0);
        line.put("baseX", baseX);
        line.put("baseY", 3200);
        line.put("heights", new int[][] { { 0, 0 }, { 0, 0 } });
        List<Object[]> locs = new ArrayList<>();
        locs.add(new Object[] { locModel, 6464, 6464, 0, locId, "o" });
        line.put("locs", locs);
        Map<String, String> names = new LinkedHashMap<>();
        names.put(String.valueOf(locId), "Goal " + locId);
        line.put("names", names);
        return line;
    }

    private static int[] row(int i, int x, int y, int anim)
    {
        return new int[] { i, x, y, 512, anim, 0, 808, 0 };
    }

    /**
     * Writes a recording the way the recorder does (deferred hdr, plugins, models and pitch; team
     * lines as JSON text; the rest serialised at once) to {@code writer}:
     * Amy (0) moves every cycle and swaps poses (models 0, 1 = delta of 0) every 50; Bob (1) stands
     * still from 1002 with one pm (model 2) and a spot anim from 1500; Cy (2) leaves at 1400; Dee
     * (3) arrives at 1950 with a new model (5, delta of 4); a ball (model 20) flies 1900 to 1950;
     * Amy and Bob collide at 1920 (Bob holding the ball); app changes at 1600.
     */
    private static void record(GzipNdjsonWriter writer, Reload reload)
    {
        writer.writeDeferred(ReplayLines.hdr(233, 330, "Ref", 1_790_000_000_000L, START), false);
        writer.writeDeferred(model(10, "loc", null));
        writer.writeDeferred(model(0, "player", null));
        writer.writeDeferred(model(1, "player", 0));
        writer.writeDeferred(model(2, "player", null));
        writer.write(ReplayLines.spawn(START + 1, 0, "Amy"));
        writer.writeJson(ReplayLines.team(START + 1, 0, Teams.Team.A));
        writer.write(ReplayLines.spawn(START + 1, 1, "Bob"));
        writer.writeJson(ReplayLines.team(START + 1, 1, Teams.Team.B));
        writer.write(ReplayLines.spawn(START + 1, 2, "Cy"));
        writer.writeJson(ReplayLines.team(START + 1, 2, null));
        writer.writeDeferred(ReplayLines.plugins(START + 2, List.of(new PluginEntry("Agility", true, PluginEntry.BUILTIN),
            new PluginEntry("Zoom", true, PluginEntry.HUB))), false);
        writer.writeDeferred(pitch(START + 3, 3000, 10, 501), false);
        for (int cyc = START + 2; cyc <= END; cyc++)
        {
            if (cyc == START + 5)
            {
                writer.writeDeferred(model(11, "loc", null));
                Map<String, String> names = new LinkedHashMap<>();
                names.put("502", "Goal 502");
                List<Object[]> rows = new ArrayList<>();
                rows.add(new Object[] { 11, 6720, 6464, 0, 502, "o" });
                writer.writeDeferred(ReplayLines.locs(rows, names), false);
            }
            if (cyc == 1400)
            {
                writer.write(ReplayLines.despawn(cyc, 2));
            }
            // Toggles: one folded into the base state, one just before the window, one inside it.
            if (cyc == 1100 || cyc == 1800)
            {
                writer.write(ReplayLines.pluginToggle(cyc, "Agility", cyc == 1800));
            }
            if (cyc == 1450)
            {
                writer.write(ReplayLines.pluginToggle(cyc, "Zoom", false));
            }
            if (reload == Reload.BEFORE_WINDOW && cyc == RELOAD_BEFORE
                || reload == Reload.INSIDE_WINDOW && cyc == RELOAD_INSIDE)
            {
                writer.writeDeferred(model(12, "loc", null));
                writer.writeDeferred(pitch(cyc, 3008, 12, 503), false);
            }
            if (cyc == 1899)
            {
                writer.writeDeferred(model(20, "ball", null));
            }
            if (cyc == 1950)
            {
                writer.writeDeferred(model(4, "player", null));
                writer.writeDeferred(model(5, "player", 4));
                writer.write(ReplayLines.spawn(cyc, 3, "Dee"));
                writer.writeJson(ReplayLines.team(cyc, 3, Teams.Team.A));
            }
            List<int[]> rows = new ArrayList<>();
            rows.add(row(0, 6400 + cyc % 300, 6400, cyc / 50 % 2 == 0 ? 808 : 819));
            if (cyc == START + 2)
            {
                rows.add(row(1, 7000, 7000, 808));
                rows.add(row(2, 7100, 7000, 808));
            }
            if (cyc == 1950)
            {
                rows.add(row(3, 6000, 6000, 808));
            }
            writer.write(ReplayLines.poses(cyc, rows));
            List<int[]> pms = new ArrayList<>();
            if (cyc % 50 == 0 || cyc == START + 2)
            {
                pms.add(new int[] { 0, cyc / 50 % 2 == 0 ? 0 : 1 });
            }
            if (cyc == START + 2)
            {
                pms.add(new int[] { 1, 2 });
                pms.add(new int[] { 2, 2 });
            }
            if (cyc == 1950)
            {
                pms.add(new int[] { 3, 5 });
            }
            if (!pms.isEmpty())
            {
                writer.write(ReplayLines.playerModels(cyc, pms));
            }
            if (cyc == 1500)
            {
                writer.write(ReplayLines.spots(cyc, 1, new int[] { 1234, 0, 92 }));
            }
            if (cyc >= 1900 && cyc <= 1950)
            {
                writer.write(ReplayLines.ball(cyc, new Ball(1528, 1890, 6500, 6600, -300, 256, null), 20));
            }
            if (cyc == 1920)
            {
                writer.write(ReplayLines.event(cyc, collision()));
            }
            if (cyc % 30 == 0)
            {
                writer.write(ReplayLines.tick(cyc, cyc / 30));
                if (cyc == 1020 || cyc == 1620)
                {
                    int look = cyc == 1020 ? 1 : 2;
                    for (int i = 0; i < 3; i++)
                    {
                        if (i == 2 && cyc > 1400)
                        {
                            continue;
                        }
                        writer.write(ReplayLines.appearance(cyc, i,
                            new Appearance("p" + i, 0, new int[] { look, i }, new int[] { 0, 0, 0, 0, 0 })));
                    }
                }
                if (cyc == 1980)
                {
                    writer.write(ReplayLines.appearance(cyc, 3,
                        new Appearance("Dee", 1, new int[] { 3 }, new int[] { 1, 1, 1, 1, 1 })));
                }
                List<int[]> tiles = new ArrayList<>();
                tiles.add(new int[] { 0, 6464 + cyc % 300 / 128 * 128, 6464 });
                if (cyc == 1020)
                {
                    tiles.add(new int[] { 1, 7040, 7040 });
                }
                writer.write(ReplayLines.trueTiles(cyc, tiles));
            }
        }
    }

    private static Collision collision()
    {
        return new Collision("Amy Two", "Bob", List.of("Bob"), 1_790_000_100_000L, 1_790_000_100_600L, 50, 51, 330,
            0, 0, 0, 50, 50, 12);
    }

    /** A recording written through a real writer with the ring tapped; returns the snapshot taken at its end. */
    private ReplayRing.Snapshot recordAndSnapshot(Reload reload, ReplayRing ring, List<String> fileLines)
        throws Exception
    {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GzipNdjsonWriter writer = new GzipNdjsonWriter(GSON, ReplayFileName.SUFFIX, () -> executor,
            System::currentTimeMillis, GzipNdjsonWriter.MAX_PENDING_BYTES);
        writer.setTap(ring);
        Path file = temp.newFolder().toPath().resolve("game.rflr.gz");
        writer.open(file);
        record(writer, reload);
        CompletableFuture<ReplayRing.Snapshot> snapshot = new CompletableFuture<>();
        writer.enqueue(() -> snapshot.complete(ring.snapshot()));
        writer.close().get(10, TimeUnit.SECONDS);
        writer.shutdown().get(10, TimeUnit.SECONDS);
        executor.shutdown();
        fileLines.addAll(gunzipLines(file));
        return snapshot.get(10, TimeUnit.SECONDS);
    }

    private static List<String> gunzipLines(Path file) throws IOException
    {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
            new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8)))
        {
            return in.lines().collect(Collectors.toList());
        }
    }

    private static List<String> clipLines(ReplayRing.Snapshot snapshot, ClipBuilder.Plan plan) throws IOException
    {
        List<String> out = new ArrayList<>();
        ClipBuilder.write(GSON, snapshot, plan, out::add);
        return out;
    }

    private static JsonObject json(String line)
    {
        return new JsonParser().parse(line).getAsJsonObject();
    }

    private static String type(String line)
    {
        return json(line).get("t").getAsString();
    }

    private static Integer cyc(String line)
    {
        JsonObject o = json(line);
        return o.has("cyc") ? o.get("cyc").getAsInt() : null;
    }

    // ---- The clip ----

    @Test
    public void aClipHoldsExactlyTheWindowLinesAfterAValidStartState() throws Exception
    {
        ReplayRing ring = new ReplayRing(KEEP_SECONDS, ReplayRing.DEFAULT_CAP_BYTES);
        List<String> source = new ArrayList<>();
        ReplayRing.Snapshot snapshot = recordAndSnapshot(Reload.NONE, ring, source);
        assertTrue("the ring has evicted lines into the base state", snapshot.evictedThrough > START);

        ClipBuilder.Plan plan = ClipBuilder.plan(snapshot, CLIP);
        assertEquals(END - CLIP, plan.fromCyc);
        assertEquals(END, plan.toCyc);
        assertEquals(10, plan.seconds);
        assertEquals(ClipBuilder.Shortened.NONE, plan.shortened);
        List<String> clip = clipLines(snapshot, plan);
        assertEquals(plan.lines, clip.size());

        // The window: every source line with cyc in [from, to], in order, at the clip's end.
        List<String> window = source.stream().filter(l -> cyc(l) != null && !"hdr".equals(type(l))
            && !"plugins".equals(type(l)) && cyc(l) >= plan.fromCyc && cyc(l) <= plan.toCyc)
            .collect(Collectors.toList());
        assertTrue(window.size() > 500);
        assertEquals(window, clip.subList(clip.size() - window.size(), clip.size()));

        // hdr first, with the clip object; then plugins.
        JsonObject hdr = json(clip.get(0));
        assertEquals("hdr", hdr.get("t").getAsString());
        assertEquals(plan.fromCyc, hdr.get("cyc").getAsInt());
        assertEquals(2, hdr.get("models").getAsInt());
        assertEquals("{\"source\":\"game.rflr.gz\",\"fromCyc\":1500,\"toCyc\":2000,\"seconds\":10}",
            hdr.get("clip").toString());
        // The plugin list as it stood at the clip's start: both toggles before the window applied.
        assertEquals("{\"t\":\"plugins\",\"cyc\":1500,\"list\":[{\"name\":\"Agility\",\"enabled\":false,"
            + "\"source\":\"builtin\"},{\"name\":\"Zoom\",\"enabled\":false,\"source\":\"hub\"}]}", clip.get(1));
        assertTrue("the toggle inside the window stays a window line",
            window.contains("{\"t\":\"plugin_toggle\",\"cyc\":1800,\"name\":\"Agility\",\"enabled\":true}"));

        // No locs lines, one pitch before the window, every line's cyc inside the clip.
        assertFalse(clip.stream().anyMatch(l -> "locs".equals(type(l))));
        for (String line : clip)
        {
            Integer at = cyc(line);
            if (at != null)
            {
                assertTrue(line, at >= plan.fromCyc && at <= plan.toCyc);
            }
        }

        WebsiteRules rec = WebsiteRules.parse(clip);
        rec.assertModelsBeforeUse();
        assertEquals(plan.fromCyc, rec.startCyc);
        assertEquals(1, rec.pitches.size());
        assertEquals("the first pitch with its locs line merged", Set.of(10, 11), rec.pitchLocModels(0));
        assertEquals(Set.of("501", "502"), rec.locNames);

        // Present at 1500: Amy, Bob (not Cy, who left at 1400; not Dee, who arrives at 1950).
        assertEquals(Set.of(0, 1), rec.presentAt(plan.fromCyc));
        for (int i : List.of(0, 1))
        {
            assertEquals("spawn at from", plan.fromCyc, rec.spawnCyc(i));
            assertEquals("app at from", Integer.valueOf(plan.fromCyc), rec.firstAt(i, "app"));
            assertEquals("pm at from", Integer.valueOf(plan.fromCyc), rec.firstAt(i, "pm"));
            assertEquals("f at from", Integer.valueOf(plan.fromCyc), rec.firstAt(i, "f"));
            assertEquals("team at from", Integer.valueOf(plan.fromCyc), rec.firstAt(i, "team"));
        }
        // Bob stood still from 1002: his row, model, look and team come from long before the window.
        assertEquals(List.of(7000, 7000), rec.rowAt(1, plan.fromCyc));
        assertEquals(Integer.valueOf(2), rec.modelAt(1, plan.fromCyc));
        assertEquals("\"B\"", rec.teamAt(1, plan.fromCyc));
        assertEquals("the look in force at 1500 (from 1020), not the later one", "[1,1]", rec.appAt(1, plan.fromCyc));
        // Bob's spot anim started at 1500 itself: it comes through as a window line.
        assertTrue(window.stream().anyMatch(l -> "spot".equals(type(l))));
        // Dee arrives in the window with her delta model and its base.
        assertTrue(rec.models.containsKey(4) && rec.models.containsKey(5));
        assertTrue(rec.models.containsKey(20));
        assertEquals("unused models are left out", false, rec.models.containsKey(12));
        assertNotNull(plan.collision);
        assertEquals("Bob", plan.collision.ball.get(0));
    }

    @Test
    public void theStartStateCarriesALongRunningSpotAnim() throws Exception
    {
        // A clip from 1600: Bob's spot anim (from 1500) is in the start state, at the clip's start.
        ReplayRing ring = new ReplayRing(KEEP_SECONDS, ReplayRing.DEFAULT_CAP_BYTES);
        ReplayRing.Snapshot snapshot = recordAndSnapshot(Reload.NONE, ring, new ArrayList<>());
        ClipBuilder.Plan plan = ClipBuilder.plan(snapshot, 400);
        List<String> clip = clipLines(snapshot, plan);
        List<String> spots = clip.stream().filter(l -> "spot".equals(type(l))).collect(Collectors.toList());
        assertEquals(1, spots.size());
        assertEquals(Integer.valueOf(1600), cyc(spots.get(0)));
        assertEquals("the look in force at 1600 is the 1020 one", "[1,0]", rec(clip).appAt(0, 1600));
        assertEquals("then the 1620 one", "[2,0]", rec(clip).appAt(0, 1620));
    }

    private static WebsiteRules rec(List<String> clip)
    {
        return WebsiteRules.parse(clip);
    }

    @Test
    public void aReloadBeforeTheWindowStartsTheClipOnTheReloadedPitch() throws Exception
    {
        ReplayRing ring = new ReplayRing(KEEP_SECONDS, ReplayRing.DEFAULT_CAP_BYTES);
        ReplayRing.Snapshot snapshot = recordAndSnapshot(Reload.BEFORE_WINDOW, ring, new ArrayList<>());
        assertEquals("the first pitch can no longer start a clip, so it was let go", 1, snapshot.pitches.size());
        ClipBuilder.Plan plan = ClipBuilder.plan(snapshot, CLIP);
        List<String> clip = clipLines(snapshot, plan);
        WebsiteRules rec = WebsiteRules.parse(clip);
        rec.assertModelsBeforeUse();
        assertEquals(1, rec.pitches.size());
        assertEquals(3008, rec.pitches.get(0).get("baseX").getAsInt());
        assertEquals(plan.fromCyc, rec.pitches.get(0).get("cyc").getAsInt());
        assertEquals(Set.of(12), rec.pitchLocModels(0));
        assertFalse(rec.models.containsKey(10) || rec.models.containsKey(11));
    }

    @Test
    public void aReloadInsideTheWindowComesThroughInPlace() throws Exception
    {
        ReplayRing ring = new ReplayRing(KEEP_SECONDS, ReplayRing.DEFAULT_CAP_BYTES);
        List<String> source = new ArrayList<>();
        ReplayRing.Snapshot snapshot = recordAndSnapshot(Reload.INSIDE_WINDOW, ring, source);
        ClipBuilder.Plan plan = ClipBuilder.plan(snapshot, CLIP);
        List<String> clip = clipLines(snapshot, plan);
        WebsiteRules rec = WebsiteRules.parse(clip);
        rec.assertModelsBeforeUse();
        assertEquals(2, rec.pitches.size());
        assertEquals(3000, rec.pitches.get(0).get("baseX").getAsInt());
        assertEquals(Set.of(10, 11), rec.pitchLocModels(0));
        assertEquals(RELOAD_INSIDE, rec.pitches.get(1).get("cyc").getAsInt());
        assertEquals(Set.of(12), rec.pitchLocModels(1));
        // The reload pitch sits between the same timed lines as in the source (its model line went
        // to the clip's model section).
        List<String> timed = source.stream().filter(l -> !"model".equals(type(l))).collect(Collectors.toList());
        int inClip = indexOfType(clip, "pitch", 1);
        int inSource = indexOfType(timed, "pitch", 1);
        assertEquals(timed.get(inSource - 1), clip.get(inClip - 1));
        assertEquals(timed.get(inSource + 1), clip.get(inClip + 1));
        assertEquals(timed.get(inSource), clip.get(inClip));
    }

    private static int indexOfType(List<String> lines, String type, int nth)
    {
        int seen = 0;
        for (int k = 0; k < lines.size(); k++)
        {
            if (type.equals(type(lines.get(k))) && seen++ == nth)
            {
                return k;
            }
        }
        return -1;
    }

    @Test
    public void theByteCapShortensTheClipAndSaysSo() throws Exception
    {
        ReplayRing ring = new ReplayRing(KEEP_SECONDS, 40 * 1024);
        ReplayRing.Snapshot snapshot = recordAndSnapshot(Reload.NONE, ring, new ArrayList<>());
        assertTrue("the ring stays near its cap", ring.ringBytes() <= 40 * 1024);
        ClipBuilder.Plan plan = ClipBuilder.plan(snapshot, CLIP);
        assertEquals(ClipBuilder.Shortened.MEMORY_CAP, plan.shortened);
        assertTrue("shorter than asked: " + plan.fromCyc, plan.fromCyc > END - CLIP);
        assertEquals(snapshot.evictedThrough + 1, plan.fromCyc);
        List<String> clip = clipLines(snapshot, plan);
        WebsiteRules rec = WebsiteRules.parse(clip);
        rec.assertModelsBeforeUse();
        assertEquals(plan.fromCyc, rec.startCyc);
        for (int i : rec.presentAt(plan.fromCyc))
        {
            assertEquals(Integer.valueOf(plan.fromCyc), rec.firstAt(i, "pm"));
            assertEquals(Integer.valueOf(plan.fromCyc), rec.firstAt(i, "app"));
        }
        assertEquals(Set.of(0, 1), rec.presentAt(plan.fromCyc));
    }

    @Test
    public void aRecordingShorterThanTheClipGivesItAll() throws Exception
    {
        ReplayRing ring = new ReplayRing(60, ReplayRing.DEFAULT_CAP_BYTES);
        ReplayRing.Snapshot snapshot = recordAndSnapshot(Reload.NONE, ring, new ArrayList<>());
        ClipBuilder.Plan plan = ClipBuilder.plan(snapshot, 3000);
        assertEquals(START, plan.fromCyc);
        assertEquals(ClipBuilder.Shortened.NOT_ENOUGH, plan.shortened);
        WebsiteRules rec = WebsiteRules.parse(clipLines(snapshot, plan));
        rec.assertModelsBeforeUse();
        assertEquals(Set.of(10, 11), rec.pitchLocModels(0));
    }

    @Test
    public void aShorterSettingTrimsTheBufferAndALongerOneKeepsIt()
    {
        ReplayRing ring = new ReplayRing(20, ReplayRing.DEFAULT_CAP_BYTES);
        ring.opened(null);
        ring.written(ReplayLines.hdr(1, 1, "Ref", 0L, 0), "{}");
        for (int cyc = 0; cyc <= 1000; cyc++)
        {
            ring.written(ReplayLines.tick(cyc, cyc), "{\"t\":\"tick\"}");
        }
        assertEquals(1001, ring.ringSize());
        ring.setKeepSeconds(60);
        ring.written(ReplayLines.tick(1001, 1001), "{\"t\":\"tick\"}");
        assertEquals("longer: nothing dropped", 1002, ring.ringSize());
        ring.setKeepSeconds(5);
        ring.written(ReplayLines.tick(1002, 1002), "{\"t\":\"tick\"}");
        assertEquals("shorter: down to the new window", 251, ring.ringSize());
    }

    @Test
    public void nothingToCutBeforeTheFirstTimedLine()
    {
        ReplayRing ring = new ReplayRing(20, ReplayRing.DEFAULT_CAP_BYTES);
        ring.opened(null);
        assertNull(ring.snapshot());
        ring.written(ReplayLines.hdr(1, 1, "Ref", 0L, 0), "{}");
        assertNull(ring.snapshot());
    }

    @Test
    public void withCycReplacesOnlyTheLinesOwnCycle()
    {
        assertEquals("{\"t\":\"team\",\"cyc\":7,\"i\":3,\"team\":null}",
            ClipBuilder.withCyc("{\"t\":\"team\",\"cyc\":812345,\"i\":3,\"team\":null}", 7));
        assertEquals("{\"t\":\"app\",\"cyc\":9,\"i\":1,\"g\":0}", ClipBuilder.withCyc("{\"t\":\"app\",\"cyc\":-4,\"i\":1,\"g\":0}", 9));
    }

    // ---- Threads ----

    @Test
    public void savingAClipCostsTheCallerOneQueuedStep() throws Exception
    {
        ExecutorService writerThread = Executors.newSingleThreadExecutor();
        List<String> clipThreads = Collections.synchronizedList(new ArrayList<>());
        ExecutorService clipThread = Executors.newSingleThreadExecutor(task -> new Thread(() ->
        {
            clipThreads.add(Thread.currentThread().getName());
            task.run();
        }, "clip-test"));
        Path replays = temp.newFolder("replays").toPath();
        GzipNdjsonWriter writer = new GzipNdjsonWriter(GSON, ReplayFileName.SUFFIX, () -> writerThread,
            System::currentTimeMillis, GzipNdjsonWriter.MAX_PENDING_BYTES);
        ReplayRecorder recorder = new ReplayRecorder(new RflConfig()
        {
            @Override
            public boolean recordReplays()
            {
                return true;
            }

            @Override
            public int clipSeconds()
            {
                return 30;
            }
        }, writer, replays, GSON, () -> clipThread);

        assertFalse("not recording: nothing to save", recorder.saveClip(System.currentTimeMillis()));
        recorder.onClientTick(client(), true);
        assertTrue(recorder.recording());
        for (int cyc = 1; cyc <= 20; cyc++)
        {
            recorder.onPlugins(ReplayLines.pluginToggle(cyc, "Agility", cyc % 2 == 0));
        }

        // Hold the writer's queue: the snapshot and everything after it must wait for it.
        CountDownLatch hold = new CountDownLatch(1);
        writer.enqueue(() ->
        {
            try
            {
                hold.await();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
        // Twice: the first call pays one-off class loading; the second is the steady cost.
        long start = System.nanoTime();
        assertTrue(recorder.saveClip(System.currentTimeMillis()));
        long firstNanos = System.nanoTime() - start;
        start = System.nanoTime();
        assertTrue(recorder.saveClip(System.currentTimeMillis()));
        long handOffNanos = System.nanoTime() - start;
        assertEquals(ClipStatus.State.SAVING, recorder.clipStatus().getState());
        assertFalse("no IO on the caller's thread", Files.exists(replays.resolve(ClipWriter.FOLDER)));
        assertTrue("no clip work started on the caller's thread", clipThreads.isEmpty());
        System.out.println("RFL clip hand-off on the calling thread: first " + firstNanos / 1000 + " us, then "
            + handOffNanos / 1000 + " us");

        hold.countDown();
        ClipStatus status = await(recorder);
        assertEquals(status.getError(), ClipStatus.State.SAVED, status.getState());
        assertEquals(List.of("clip-test"), clipThreads);
        recorder.shutdown().get(10, TimeUnit.SECONDS);
        status = recorder.clipStatus();
        Path folder = replays.resolve("snapshot reviews");
        List<Path> clips;
        try (Stream<Path> files = Files.list(folder))
        {
            clips = files.sorted().collect(Collectors.toList());
        }
        assertEquals("two saves, the second numbered -2", 2, clips.size());
        clips = List.of(folder.resolve(status.getFileName()));
        assertEquals(status.getFileName(), clips.get(0).getFileName().toString());
        assertTrue(status.getFileName(), status.getFileName().matches(".*_w330_no-collision(-2)?[.]rflr[.]gz"));
        // A complete gzip (trailer and all) by the time SAVED shows.
        List<String> lines = gunzipLines(clips.get(0));
        assertEquals("hdr", type(lines.get(0)));
        assertTrue(json(lines.get(0)).has("clip"));
        assertEquals(status.getBytes(), Files.size(clips.get(0)));
        writerThread.shutdown();
    }

    @Test
    public void aClipWithNothingBufferedSaysSo() throws Exception
    {
        ClipWriter clips = new ClipWriter(GSON, Executors::newSingleThreadExecutor);
        clips.requested();
        clips.submit(null, CLIP, temp.getRoot().toPath(), ClipFileName.DEFAULT_TEMPLATE, 0L, ZoneId.systemDefault());
        assertEquals(ClipStatus.State.ERROR, clips.status().getState());
        assertEquals(ClipWriter.NOTHING_YET, clips.status().getError());
        clips.shutdown().get(5, TimeUnit.SECONDS);
    }

    @Test
    public void aTakenClipNameIsNumbered() throws Exception
    {
        ReplayRing ring = new ReplayRing(KEEP_SECONDS, ReplayRing.DEFAULT_CAP_BYTES);
        ReplayRing.Snapshot snapshot = recordAndSnapshot(Reload.NONE, ring, new ArrayList<>());
        ClipWriter clips = new ClipWriter(GSON, Executors::newSingleThreadExecutor);
        Path replays = temp.newFolder("numbered").toPath();
        for (int k = 0; k < 2; k++)
        {
            clips.submit(snapshot, CLIP, replays, "same", 0L, ZoneId.systemDefault());
        }
        clips.shutdown().get(10, TimeUnit.SECONDS);
        assertTrue(Files.exists(replays.resolve("snapshot reviews").resolve("same.rflr.gz")));
        assertTrue(Files.exists(replays.resolve("snapshot reviews").resolve("same-2.rflr.gz")));
    }

    private static ClipStatus await(ReplayRecorder recorder) throws InterruptedException
    {
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until)
        {
            ClipStatus status = recorder.clipStatus();
            if (status.getState() == ClipStatus.State.SAVED || status.getState() == ClipStatus.State.ERROR)
            {
                return status;
            }
            Thread.sleep(10);
        }
        return recorder.clipStatus();
    }

    /** A logged-in client with an empty scene: enough for the recorder to open a file. */
    private static Client client()
    {
        WorldView view = (WorldView) Proxy.newProxyInstance(ReplayClipTest.class.getClassLoader(),
            new Class<?>[] { WorldView.class }, (proxy, method, args) ->
            {
                if ("players".equals(method.getName()))
                {
                    return emptySet(method.getReturnType());
                }
                return defaultValue(method.getReturnType());
            });
        return (Client) Proxy.newProxyInstance(ReplayClipTest.class.getClassLoader(), new Class<?>[] { Client.class },
            (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getGameState":
                        return GameState.LOGGED_IN;
                    case "getTopLevelWorldView":
                        return view;
                    case "getProjectiles":
                        return method.getReturnType().isInstance(new ArrayDeque<>()) ? new ArrayDeque<>()
                            : emptySet(method.getReturnType());
                    case "getWorld":
                        return 330;
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
    }

    private static Object emptySet(Class<?> type)
    {
        return Proxy.newProxyInstance(ReplayClipTest.class.getClassLoader(), new Class<?>[] { type },
            (proxy, method, args) -> "iterator".equals(method.getName()) ? Collections.emptyIterator()
                : defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type)
    {
        if (type == boolean.class)
        {
            return false;
        }
        if (type == int.class)
        {
            return 0;
        }
        if (type == long.class)
        {
            return 0L;
        }
        if (type == float.class)
        {
            return 0f;
        }
        if (type == double.class)
        {
            return 0.0;
        }
        if (type == short.class)
        {
            return (short) 0;
        }
        if (type == byte.class)
        {
            return (byte) 0;
        }
        return null;
    }

    // ---- The website's line rules ----

    /**
     * Reads lines the way the replay website's {@code parseRecording} does (rfl-pages
     * {@code src/replay/timeline.ts}): model lines need no cycle and the first of an id wins;
     * {@code locs} lines merge into the first pitch; every other line needs a numeric {@code cyc};
     * the timeline starts at {@code hdr.cyc}; pitches sort by cycle; a player's state at a cycle is
     * their latest row at or before it. Also checks the self-contained rule: each model, and each
     * delta base, is defined before the first line that uses it.
     */
    static final class WebsiteRules
    {
        final Map<Integer, JsonObject> models = new TreeMap<>();
        final List<JsonObject> pitches = new ArrayList<>();
        final List<JsonArray> extraLocs = new ArrayList<>();
        final Set<String> locNames = new HashSet<>();
        final Map<Integer, List<int[]>> spawns = new TreeMap<>();
        /** Per player and line type, every (cyc, value) in file order. */
        final Map<Integer, Map<String, List<Object[]>>> tracks = new TreeMap<>();
        final List<String> problems = new ArrayList<>();
        int startCyc;

        static WebsiteRules parse(List<String> lines)
        {
            WebsiteRules rec = new WebsiteRules();
            JsonObject hdr = null;
            int minCyc = Integer.MAX_VALUE;
            for (String text : lines)
            {
                JsonObject line = new JsonParser().parse(text).getAsJsonObject();
                String t = line.get("t").getAsString();
                if ("hdr".equals(t))
                {
                    hdr = line;
                }
                if ("model".equals(t))
                {
                    int id = line.get("id").getAsInt();
                    if (line.has("base") && !rec.models.containsKey(line.get("base").getAsInt()))
                    {
                        rec.problems.add("model " + id + " before its base");
                    }
                    rec.models.putIfAbsent(id, line);
                    continue;
                }
                if ("locs".equals(t))
                {
                    rec.extraLocs.add(line.getAsJsonArray("locs"));
                    rec.names(line);
                    continue;
                }
                if (!line.has("cyc"))
                {
                    continue;
                }
                int cyc = line.get("cyc").getAsInt();
                minCyc = Math.min(minCyc, cyc);
                rec.take(t, cyc, line);
            }
            rec.startCyc = hdr != null ? hdr.get("cyc").getAsInt() : minCyc;
            rec.pitches.sort((a, b) -> Integer.compare(a.get("cyc").getAsInt(), b.get("cyc").getAsInt()));
            return rec;
        }

        private void names(JsonObject line)
        {
            if (line.has("names"))
            {
                locNames.addAll(line.getAsJsonObject("names").keySet());
            }
        }

        private void track(int i, String type, int cyc, Object value)
        {
            tracks.computeIfAbsent(i, k -> new LinkedHashMap<>()).computeIfAbsent(type, k -> new ArrayList<>())
                .add(new Object[] { cyc, value });
        }

        private void take(String t, int cyc, JsonObject line)
        {
            switch (t)
            {
                case "spawn":
                {
                    List<int[]> spans = spawns.computeIfAbsent(line.get("i").getAsInt(), k -> new ArrayList<>());
                    if (spans.isEmpty() || spans.get(spans.size() - 1)[1] != Integer.MAX_VALUE)
                    {
                        spans.add(new int[] { cyc, Integer.MAX_VALUE });
                    }
                    break;
                }
                case "despawn":
                {
                    List<int[]> spans = spawns.get(line.get("i").getAsInt());
                    if (spans != null && spans.get(spans.size() - 1)[1] == Integer.MAX_VALUE)
                    {
                        spans.get(spans.size() - 1)[1] = cyc;
                    }
                    break;
                }
                case "app":
                    track(line.get("i").getAsInt(), "app", cyc, line.get("eq").toString());
                    break;
                case "team":
                    track(line.get("i").getAsInt(), "team", cyc, line.get("team").toString());
                    break;
                case "f":
                    for (JsonElement r : line.getAsJsonArray("p"))
                    {
                        JsonArray row = r.getAsJsonArray();
                        track(row.get(0).getAsInt(), "f", cyc, List.of(row.get(1).getAsInt(), row.get(2).getAsInt()));
                    }
                    break;
                case "pm":
                    for (JsonElement r : line.getAsJsonArray("p"))
                    {
                        JsonArray row = r.getAsJsonArray();
                        int model = row.get(1).getAsInt();
                        use(model, line);
                        track(row.get(0).getAsInt(), "pm", cyc, model);
                    }
                    break;
                case "ball":
                    if (line.has("m"))
                    {
                        use(line.get("m").getAsInt(), line);
                    }
                    break;
                case "pitch":
                    pitches.add(line);
                    names(line);
                    for (JsonElement r : line.getAsJsonArray("locs"))
                    {
                        use(r.getAsJsonArray().get(0).getAsInt(), line);
                    }
                    break;
                default:
                    break;
            }
        }

        private void use(int model, JsonObject line)
        {
            if (!models.containsKey(model))
            {
                problems.add("model " + model + " used before it is defined: " + line);
            }
        }

        void assertModelsBeforeUse()
        {
            for (JsonArray rows : extraLocs)
            {
                for (JsonElement r : rows)
                {
                    if (!models.containsKey(r.getAsJsonArray().get(0).getAsInt()))
                    {
                        problems.add("locs model missing");
                    }
                }
            }
            assertTrue(problems.toString(), problems.isEmpty());
        }

        /** The first pitch takes the locs lines too. */
        Set<Integer> pitchLocModels(int k)
        {
            Set<Integer> out = new HashSet<>();
            for (JsonElement r : pitches.get(k).getAsJsonArray("locs"))
            {
                out.add(r.getAsJsonArray().get(0).getAsInt());
            }
            if (k == 0)
            {
                for (JsonArray rows : extraLocs)
                {
                    for (JsonElement r : rows)
                    {
                        out.add(r.getAsJsonArray().get(0).getAsInt());
                    }
                }
            }
            return out;
        }

        Set<Integer> presentAt(int cyc)
        {
            Set<Integer> out = new HashSet<>();
            for (Map.Entry<Integer, List<int[]>> player : spawns.entrySet())
            {
                for (int[] span : player.getValue())
                {
                    if (span[0] <= cyc && cyc < span[1])
                    {
                        out.add(player.getKey());
                    }
                }
            }
            return out;
        }

        int spawnCyc(int i)
        {
            return spawns.get(i).get(0)[0];
        }

        Integer firstAt(int i, String type)
        {
            List<Object[]> values = tracks.getOrDefault(i, Map.of()).get(type);
            return values == null || values.isEmpty() ? null : (Integer) values.get(0)[0];
        }

        private Object at(int i, String type, int cyc)
        {
            Object out = null;
            for (Object[] value : tracks.getOrDefault(i, Map.of()).getOrDefault(type, List.of()))
            {
                if ((Integer) value[0] <= cyc)
                {
                    out = value[1];
                }
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        List<Integer> rowAt(int i, int cyc)
        {
            return (List<Integer>) at(i, "f", cyc);
        }

        Integer modelAt(int i, int cyc)
        {
            return (Integer) at(i, "pm", cyc);
        }

        String teamAt(int i, int cyc)
        {
            return (String) at(i, "team", cyc);
        }

        String appAt(int i, int cyc)
        {
            return (String) at(i, "app", cyc);
        }
    }
}
