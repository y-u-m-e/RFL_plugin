package com.rfl.replay;

import sh.yumekui.toolkit.io.WriterState;
import com.rfl.Fixtures;
import sh.yumekui.toolkit.io.GzipNdjsonWriter;

import com.rfl.RflConfig;
import com.rfl.contact.Collision;
import com.rfl.incomplete.Incomplete;
import com.rfl.log.CollisionLog;
import com.rfl.log.PluginEntry;
import com.rfl.teams.Teams;
import sh.yumekui.toolkit.model.ModelCapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * {@link ReplayRecorder}: when it records, what it names the file, and how it hears about
 * finished collisions and incompletes (the {@link CollisionLog} listener).
 */
public class ReplayRecorderTest
{
    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void recordsOnlyWithSettingLoggedInAndInPoh()
    {
        for (int bits = 0; bits < 8; bits++)
        {
            boolean setting = (bits & 1) != 0;
            boolean loggedIn = (bits & 2) != 0;
            boolean inPoh = (bits & 4) != 0;
            assertEquals("setting=" + setting + " loggedIn=" + loggedIn + " inPoh=" + inPoh,
                bits == 7, ReplayRecorder.records(setting, loggedIn, inPoh));
        }
    }

    @Test
    public void theDefaultFileNameIsDateTimeAndWorld()
    {
        long epochMs = LocalDateTime.of(2026, 10, 2, 18, 5, 9)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals("2026-10-02_180509_w330.rflr.gz", ReplayFileName.fileName(ReplayFileName.DEFAULT_TEMPLATE,
            epochMs, ZoneId.systemDefault(), 330, null));
    }

    @Test
    public void collisionLogNotifiesListenerEvenWhenNotSaving() throws Exception
    {
        Path dir = temp.getRoot().toPath().resolve("rfl").resolve("collisions");
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CollisionLog log = new CollisionLog(new GsonBuilder().create(), executor, dir, () -> false);
        List<Object> heard = new ArrayList<>();
        log.setListener(heard::add);

        Collision collision = Fixtures.collision("Amy", "Zed").at(1_000L, 500).build();
        log.record(collision);

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, heard.size());
        assertSame(collision, heard.get(0));
        assertFalse("nothing saved with Save collisions off", dir.toFile().exists());
    }

    @Test
    public void pluginLinesSitBesideTheOtherLineTypes()
    {
        Gson gson = new GsonBuilder().create();
        JsonObject plugins = new JsonParser().parse(gson.toJson(ReplayLines.plugins(812345,
            List.of(new PluginEntry("Block Tracker", true, PluginEntry.HUB))))).getAsJsonObject();
        assertEquals("{\"t\":\"plugins\",\"cyc\":812345,\"list\":[{\"name\":\"Block Tracker\","
            + "\"enabled\":true,\"source\":\"hub\"}]}", plugins.toString());

        JsonObject toggle = new JsonParser().parse(gson.toJson(
            ReplayLines.pluginToggle(812400, "Agility", false))).getAsJsonObject();
        assertEquals("{\"t\":\"plugin_toggle\",\"cyc\":812400,\"name\":\"Agility\",\"enabled\":false}",
            toggle.toString());
    }

    @Test
    public void onlyModelLinesAreSerialisedOffThread()
    {
        // Model lines (large, immutable geometry) go through writeDeferred; pm and ball rows do not.
        assertTrue(ReplayLines.isModelLine(java.util.Map.of("t", "model")));
        assertFalse(ReplayLines.isModelLine(java.util.Map.of("t", "pm")));
        assertFalse(ReplayLines.isModelLine(java.util.Map.of("t", "ball")));
        assertFalse(ReplayLines.isModelLine(java.util.Map.of("t", "pitch")));
    }

    @Test
    public void modelLinesComeFirstInAFrameAndSurviveTheWriter() throws Exception
    {
        // End to end through the sampler and a real writer: the model line lands before the pm and
        // ball rows that use its id, and the file reads back as JSON.
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        Gson gson = new GsonBuilder().create();
        GzipNdjsonWriter writer = new GzipNdjsonWriter(gson, ReplayFileName.SUFFIX, () -> executor, System::currentTimeMillis, GzipNdjsonWriter.MAX_PENDING_BYTES);
        Path file = temp.getRoot().toPath().resolve("m.rflr.gz");
        ReplaySampler sampler = new ReplaySampler();
        ModelCapture.Geometry g = new ModelCapture.Geometry(new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 },
            new int[] { 0, 1, 2 }, new int[] { 9, 9, 9 });
        sampler.tick(1, 1, List.of(new Appearance("A", 0, new int[] { 1 }, new int[] { 2 })), List.of());

        writer.open(file);
        for (java.util.Map<String, Object> line : sampler.frame(5, List.of(new PlayerState("A", 1, 2, 0,
            1, 0, 808, 0, PlayerState.NO_SPOTS, () -> g, null)), List.of(new Ball(1528, 1, 1, 2, 3, 0,
            () -> g))))
        {
            if (ReplayLines.isModelLine(line))
            {
                writer.writeDeferred(line);
            }
            else
            {
                writer.write(line);
            }
        }
        writer.close().get(5, TimeUnit.SECONDS);
        executor.shutdown();

        List<String> types = new ArrayList<>();
        for (JsonObject o : readBack(file))
        {
            types.add(o.get("t").getAsString());
        }
        assertTrue(types.toString(), types.indexOf("model") < types.indexOf("pm"));
        assertTrue(types.toString(), types.lastIndexOf("model") < types.indexOf("ball"));
        assertEquals(2, types.stream().filter("model"::equals).count());
    }

    private static List<JsonObject> readBack(Path file) throws Exception
    {
        List<JsonObject> out = new ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
            new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(file)),
            java.nio.charset.StandardCharsets.UTF_8)))
        {
            String s;
            while ((s = r.readLine()) != null)
            {
                out.add(new JsonParser().parse(s).getAsJsonObject());
            }
        }
        return out;
    }

    private static int[] ints(com.google.gson.JsonElement array)
    {
        com.google.gson.JsonArray a = array.getAsJsonArray();
        int[] out = new int[a.size()];
        for (int k = 0; k < out.length; k++)
        {
            out[k] = a.get(k).getAsInt();
        }
        return out;
    }

    @Test
    public void deltaPoseRoundTripsThroughTheFile() throws Exception
    {
        // Two poses of one appearance and topology: the second is written as base + dv, and a reader
        // rebuilding v = base.v + dv gets the source geometry back exactly.
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        GzipNdjsonWriter writer = new GzipNdjsonWriter(new GsonBuilder().create(), ReplayFileName.SUFFIX, () -> executor, System::currentTimeMillis, GzipNdjsonWriter.MAX_PENDING_BYTES);
        Path file = temp.getRoot().toPath().resolve("delta.rflr.gz");
        ReplaySampler sampler = new ReplaySampler();
        int[] faces = { 0, 1, 2, 1, 2, 3 };
        int[] colors = { 200, 10, 10, 10, 200, 10 };
        ModelCapture.Geometry first = new ModelCapture.Geometry(new int[] { 0, -100, 5, 12, -220, -7, -30, 0, 40,
            64, -64, 0 }, faces.clone(), colors.clone());
        ModelCapture.Geometry second = new ModelCapture.Geometry(new int[] { 3, -98, 5, -15, -201, 9, -30, 0, 40,
            70, -60, -2 }, faces.clone(), colors.clone());
        sampler.tick(1, 1, List.of(new Appearance("A", 0, new int[] { 1 }, new int[] { 2 })), List.of());

        writer.open(file);
        for (int c = 0; c < 2; c++)
        {
            ModelCapture.Geometry g = c == 0 ? first : second;
            for (java.util.Map<String, Object> line : sampler.frame(5 + c, List.of(new PlayerState("A",
                1, 2, 0, 100 + c, 0, 808, 0, PlayerState.NO_SPOTS, () -> g, null)), List.of()))
            {
                if (ReplayLines.isModelLine(line))
                {
                    writer.writeDeferred(line);
                }
                else
                {
                    writer.write(line);
                }
            }
        }
        writer.close().get(5, TimeUnit.SECONDS);
        executor.shutdown();

        java.util.Map<Integer, JsonObject> models = new java.util.HashMap<>();
        for (JsonObject o : readBack(file))
        {
            if ("model".equals(o.get("t").getAsString()))
            {
                models.put(o.get("id").getAsInt(), o);
            }
        }
        JsonObject delta = models.get(1);
        assertTrue(delta.toString(), delta.has("base") && delta.has("dv") && !delta.has("v"));
        JsonObject base = models.get(delta.get("base").getAsInt());
        int[] baseV = ints(base.get("v"));
        int[] dv = ints(delta.get("dv"));
        int[] rebuilt = new int[dv.length];
        for (int k = 0; k < dv.length; k++)
        {
            rebuilt[k] = baseV[k] + dv[k];
        }
        org.junit.Assert.assertArrayEquals(first.vertices, baseV);
        org.junit.Assert.assertArrayEquals(second.vertices, rebuilt);
        org.junit.Assert.assertArrayEquals(faces, ints(base.get("f")));
        org.junit.Assert.assertArrayEquals(colors, ints(base.get("c")));
    }

    @Test
    public void pluginLinesNeedAnOpenReplay() throws Exception
    {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        Path dir = temp.getRoot().toPath().resolve("replays");
        ReplayRecorder recorder = new ReplayRecorder(new RflConfig()
        {
        }, new GzipNdjsonWriter(new GsonBuilder().create(), ReplayFileName.SUFFIX, () -> executor, System::currentTimeMillis, GzipNdjsonWriter.MAX_PENDING_BYTES), dir);
        recorder.onPlugins(ReplayLines.pluginToggle(1, "Agility", true));
        recorder.stop().get(5, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertFalse(dir.toFile().exists());
    }

    @Test
    public void neverReopensAfterClientShutdown() throws Exception
    {
        // Exiting from inside a house with Record replays on: the frames after the shutdown stop
        // must not open a fresh file the dying client can't finish (left a 10-byte stub before).
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        Path dir = temp.getRoot().toPath().resolve("replays");
        ReplayRecorder recorder = new ReplayRecorder(new RflConfig()
        {
            @Override
            public boolean recordReplays()
            {
                return true;
            }
        }, new GzipNdjsonWriter(new GsonBuilder().create(), ReplayFileName.SUFFIX, () -> executor, System::currentTimeMillis, GzipNdjsonWriter.MAX_PENDING_BYTES), dir);
        recorder.shutdown().get(5, TimeUnit.SECONDS);
        // A null client would throw if the recorder tried to open a file or read the frame.
        recorder.onClientTick(null, true);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertFalse(dir.toFile().exists());
    }

    @Test
    public void locsLineHasThePitchLocsRowShape()
    {
        Gson gson = new GsonBuilder().create();
        Map<String, String> names = new java.util.LinkedHashMap<>();
        names.put("1234", "Grass");
        String json = gson.toJson(ReplayLines.locs(List.of(new Object[] { 3, 6464, 6592, -10, 1234, "g" },
            new Object[] { 4, 6720, 6464, 0, 1234, "g" }), names));
        assertEquals("{\"t\":\"locs\",\"locs\":[[3,6464,6592,-10,1234,\"g\"],[4,6720,6464,0,1234,\"g\"]],"
            + "\"names\":{\"1234\":\"Grass\"}}", json);
    }

    @Test
    public void locBudgetIsAboutOneMillisecond()
    {
        assertEquals(1_000_000L, ReplayRecorder.LOC_BUDGET_NANOS);
    }

    private static ReplaySampler samplerWith(String... names)
    {
        ReplaySampler sampler = new ReplaySampler();
        List<PlayerState> players = new ArrayList<>();
        for (String name : names)
        {
            players.add(new PlayerState(name, 100, 200, 0, -1, 0, 808, 0, PlayerState.NO_SPOTS, null, null));
        }
        sampler.frame(1, players, List.of());
        return sampler;
    }

    @Test
    public void aChatLineCarriesCycleIndexAndText()
    {
        ReplaySampler sampler = samplerWith("Amy", "Bob");
        String json = new GsonBuilder().create().toJson(ReplayLines.chat(sampler, 4321, "Bob", "3 2 1 hike"));
        assertEquals("{\"t\":\"chat\",\"cyc\":4321,\"i\":1,\"text\":\"3 2 1 hike\"}", json);
    }

    @Test
    public void chatTextLosesTagsAndIsCappedAt80()
    {
        ReplaySampler sampler = samplerWith("Amy");
        assertEquals("red text and icon", ReplayLines.chat(sampler, 1, "Amy",
            "<col=ff0000>red text</col> and <img=2>icon").get("text"));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++)
        {
            longText.append((char) ('a' + i % 26));
        }
        String capped = (String) ReplayLines.chat(sampler, 1, "Amy", longText.toString()).get("text");
        assertEquals(ReplayLines.CHAT_MAX, capped.length());
        assertEquals(longText.substring(0, 80), capped);
        assertEquals("nothing left after the tags", null, ReplayLines.chat(sampler, 1, "Amy", "<img=3>"));
    }

    @Test
    public void noChatLineWithoutARecordingOrForAPlayerNotSampled()
    {
        assertEquals(null, ReplayLines.chat(null, 1, "Amy", "hi"));
        ReplaySampler sampler = samplerWith("Amy");
        assertEquals("never sampled", null, ReplayLines.chat(sampler, 1, "Zed", "hi"));
        sampler.frame(2, List.of(), List.of());
        assertEquals("despawned", null, ReplayLines.chat(sampler, 2, "Amy", "hi"));
    }

    @Test
    public void overheadTextWritesNothingWhenNotRecording() throws Exception
    {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        Path dir = temp.getRoot().toPath().resolve("replays");
        ReplayRecorder recorder = new ReplayRecorder(new RflConfig()
        {
        }, new GzipNdjsonWriter(new GsonBuilder().create(), ReplayFileName.SUFFIX, () -> executor, System::currentTimeMillis, GzipNdjsonWriter.MAX_PENDING_BYTES), dir);
        recorder.onOverheadText(1, "Amy", "hi");
        recorder.stop().get(5, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertFalse(dir.toFile().exists());
    }

    @Test
    public void aTeamLineFollowsEachSpawnWithNullForUnassigned()
    {
        ReplaySampler sampler = new ReplaySampler();
        List<Map<String, Object>> frame = sampler.frame(7, List.of(
            new PlayerState("Amy", 100, 200, 0, -1, 0, 808, 0, PlayerState.NO_SPOTS, null, null),
            new PlayerState("Zed", 110, 200, 0, -1, 0, 808, 0, PlayerState.NO_SPOTS, null, null)), List.of());
        List<?> lines = ReplayLines.withTeams(frame, name -> "Amy".equals(name) ? Teams.Team.A : null);
        List<String> types = new ArrayList<>();
        for (Object line : lines)
        {
            types.add(line instanceof String ? "team" : (String) ((Map<?, ?>) line).get("t"));
        }
        assertEquals(List.of("spawn", "team", "spawn", "team", "f"), types);
        assertEquals("{\"t\":\"team\",\"cyc\":7,\"i\":0,\"team\":\"A\"}", lines.get(1));
        assertEquals("{\"t\":\"team\",\"cyc\":7,\"i\":1,\"team\":null}", lines.get(3));
        // The hand-built line is valid JSON with the null kept.
        JsonObject parsed = new JsonParser().parse((String) lines.get(3)).getAsJsonObject();
        assertTrue(parsed.has("team") && parsed.get("team").isJsonNull());
    }

    @Test
    public void aChangeWritesATeamLineForThatPlayerOnly()
    {
        ReplaySampler sampler = samplerWith("Ref Bob", "Amy");
        assertEquals(List.of("{\"t\":\"team\",\"cyc\":9,\"i\":0,\"team\":\"B\"}"),
            ReplayLines.teams(sampler, 9, "ref_bob", name -> Teams.Team.B));
        assertEquals("not sampled", List.of(), ReplayLines.teams(sampler, 9, "Zed", name -> Teams.Team.B));
    }

    @Test
    public void clearTeamsWritesNullForEverySampledPlayer()
    {
        ReplaySampler sampler = samplerWith("Amy", "Bob", "Cy");
        assertEquals(List.of(
            "{\"t\":\"team\",\"cyc\":3,\"i\":0,\"team\":null}",
            "{\"t\":\"team\",\"cyc\":3,\"i\":1,\"team\":null}",
            "{\"t\":\"team\",\"cyc\":3,\"i\":2,\"team\":null}"),
            ReplayLines.teams(sampler, 3, null, name -> null));
    }

    @Test
    public void anIncompleteEvIsStampedWithItsCatchCycle() throws Exception
    {
        // With no file open nothing is written, so check the line the recorder builds through the log shape.
        Incomplete i = Fixtures.incomplete("Amy").contacts("Zed").at(1L, 1099).tile(0, 0, 0, 1, 2).catchCycle(36187).build();
        assertEquals(36187, i.catchCyc);
        assertEquals("kept for older readers", 1099, i.tick);
        assertEquals(-1, Fixtures.incomplete("Amy").contacts("Zed").at(1L, 1099).tile(0, 0, 0, 1, 2).build().catchCyc);
        Map<String, Object> ev = ReplayLines.event(36211, i);
        assertEquals(List.of("t", "cyc", "catchCyc", "e"), new ArrayList<>(ev.keySet()));
        assertEquals(36187, ev.get("cyc"));
        assertEquals(36187, ev.get("catchCyc"));
        Map<String, Object> collision = ReplayLines.event(36211, "a collision");
        assertEquals(36211, collision.get("cyc"));
        assertFalse(collision.containsKey("catchCyc"));
    }

    @Test
    public void thePanelsReplayStateFollowsTheWritersState()
    {
        // The panel says "recording" where the toolkit writer says "writing"; the rest map one to one.
        assertEquals(ReplayState.IDLE, ReplayState.of(WriterState.IDLE));
        assertEquals(ReplayState.RECORDING, ReplayState.of(WriterState.WRITING));
        assertEquals(ReplayState.SAVING, ReplayState.of(WriterState.SAVING));
        assertEquals(ReplayState.SAVED, ReplayState.of(WriterState.SAVED));
        assertEquals(ReplayState.ERROR, ReplayState.of(WriterState.ERROR));
    }
}
