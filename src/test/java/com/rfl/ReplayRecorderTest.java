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
            0, 12);
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
