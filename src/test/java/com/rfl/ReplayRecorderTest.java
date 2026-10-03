package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
    public void fileNameFormat()
    {
        long epochMs = LocalDateTime.of(2026, 10, 2, 18, 5, 9)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals("2026-10-02_180509_w330.rflr.gz", ReplayRecorder.fileName(epochMs, 330));
    }

    @Test
    public void collisionLogNotifiesListenerEvenWhenNotSaving() throws Exception
    {
        Path dir = temp.getRoot().toPath().resolve("rfl").resolve("collisions");
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CollisionLog log = new CollisionLog(new GsonBuilder().create(), executor, dir, () -> false);
        List<Object> heard = new ArrayList<>();
        log.setListener(heard::add);

        Collision collision = new Collision("Amy", "Zed", List.of("Zed"), 1_000L, 1_500L, 10, 11, 330, 1890, 5730,
            0, 14, 44, 12);
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
        JsonObject plugins = new JsonParser().parse(gson.toJson(ReplayRecorder.pluginsLine(812345,
            List.of(new PluginEntry("Block Tracker", true, PluginEntry.HUB))))).getAsJsonObject();
        assertEquals("{\"t\":\"plugins\",\"cyc\":812345,\"list\":[{\"name\":\"Block Tracker\","
            + "\"enabled\":true,\"source\":\"hub\"}]}", plugins.toString());

        JsonObject toggle = new JsonParser().parse(gson.toJson(
            ReplayRecorder.pluginToggleLine(812400, "Agility", false))).getAsJsonObject();
        assertEquals("{\"t\":\"plugin_toggle\",\"cyc\":812400,\"name\":\"Agility\",\"enabled\":false}",
            toggle.toString());
    }

    @Test
    public void onlyModelLinesAreSerialisedOffThread()
    {
        // Model lines (large, immutable geometry) go through writeDeferred; pm and ball rows do not.
        assertTrue(ReplayRecorder.isModelLine(java.util.Map.of("t", "model")));
        assertFalse(ReplayRecorder.isModelLine(java.util.Map.of("t", "pm")));
        assertFalse(ReplayRecorder.isModelLine(java.util.Map.of("t", "ball")));
        assertFalse(ReplayRecorder.isModelLine(java.util.Map.of("t", "pitch")));
    }

    @Test
    public void modelLinesComeFirstInAFrameAndSurviveTheWriter() throws Exception
    {
        // End to end through the sampler and a real writer: the model line lands before the pm and
        // ball rows that use its id, and the file reads back as JSON.
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        Gson gson = new GsonBuilder().create();
        ReplayWriter writer = new ReplayWriter(gson, executor);
        Path file = temp.getRoot().toPath().resolve("m.rflr.gz");
        ReplaySampler sampler = new ReplaySampler();
        ModelCapture.Geometry g = new ModelCapture.Geometry(new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 },
            new int[] { 0, 1, 2 }, new int[] { 9, 9, 9 });
        sampler.tick(1, 1, List.of(new ReplaySampler.Appearance("A", 0, new int[] { 1 }, new int[] { 2 })));

        writer.open(file);
        for (java.util.Map<String, Object> line : sampler.frame(5, List.of(new ReplaySampler.PlayerState("A", 1, 2, 0,
            1, 0, 808, 0, ReplaySampler.NO_SPOTS, () -> g)), List.of(new ReplaySampler.Ball(1528, 1, 1, 2, 3, 0,
            () -> g))))
        {
            if (ReplayRecorder.isModelLine(line))
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
        ReplayWriter writer = new ReplayWriter(new GsonBuilder().create(), executor);
        Path file = temp.getRoot().toPath().resolve("delta.rflr.gz");
        ReplaySampler sampler = new ReplaySampler();
        int[] faces = { 0, 1, 2, 1, 2, 3 };
        int[] colors = { 200, 10, 10, 10, 200, 10 };
        ModelCapture.Geometry first = new ModelCapture.Geometry(new int[] { 0, -100, 5, 12, -220, -7, -30, 0, 40,
            64, -64, 0 }, faces.clone(), colors.clone());
        ModelCapture.Geometry second = new ModelCapture.Geometry(new int[] { 3, -98, 5, -15, -201, 9, -30, 0, 40,
            70, -60, -2 }, faces.clone(), colors.clone());
        sampler.tick(1, 1, List.of(new ReplaySampler.Appearance("A", 0, new int[] { 1 }, new int[] { 2 })));

        writer.open(file);
        for (int c = 0; c < 2; c++)
        {
            ModelCapture.Geometry g = c == 0 ? first : second;
            for (java.util.Map<String, Object> line : sampler.frame(5 + c, List.of(new ReplaySampler.PlayerState("A",
                1, 2, 0, 100 + c, 0, 808, 0, ReplaySampler.NO_SPOTS, () -> g)), List.of()))
            {
                if (ReplayRecorder.isModelLine(line))
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
        }, new ReplayWriter(new GsonBuilder().create(), executor), dir);
        recorder.onPlugins(ReplayRecorder.pluginToggleLine(1, "Agility", true));
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
        }, new ReplayWriter(new GsonBuilder().create(), executor), dir);
        recorder.shutdown().get(5, TimeUnit.SECONDS);
        // A null client would throw if the recorder tried to open a file or read the frame.
        recorder.onClientTick(null, true);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertFalse(dir.toFile().exists());
    }
}
