package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** {@link PluginLog}: the local user's own plugin list in a local day file, nothing sent. */
public class PluginLogTest
{
    private static final long NOW = 1_790_000_000_000L;

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private static List<PluginEntry> plugins()
    {
        return List.of(
            new PluginEntry("Agility", true, PluginEntry.BUILTIN),
            new PluginEntry("Block Tracker", false, PluginEntry.HUB),
            new PluginEntry("RFL Audit", true, PluginEntry.SIDELOADED));
    }

    private static void drain(ScheduledExecutorService executor) throws InterruptedException
    {
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static JsonObject parse(String line)
    {
        return new JsonParser().parse(line).getAsJsonObject();
    }

    @Test
    public void snapshotLineShape() throws Exception
    {
        Path dir = temp.getRoot().toPath().resolve("rfl").resolve("plugins");
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        PluginLog log = new PluginLog(new GsonBuilder().create(), executor, dir, () -> true);

        log.snapshot(NOW, "Ref", 330, plugins());
        drain(executor);

        Path file = dir.resolve(CollisionLog.fileName(NOW));
        assertTrue(file.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl"));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        JsonObject o = parse(lines.get(0));
        assertEquals(new TreeSet<>(List.of("type", "timeMs", "rsn", "world", "plugins")), new TreeSet<>(o.keySet()));
        assertEquals("snapshot", o.get("type").getAsString());
        assertEquals(NOW, o.get("timeMs").getAsLong());
        assertEquals("Ref", o.get("rsn").getAsString());
        assertEquals(330, o.get("world").getAsInt());
        JsonArray list = o.getAsJsonArray("plugins");
        assertEquals(3, list.size());
        JsonObject first = list.get(0).getAsJsonObject();
        assertEquals(new TreeSet<>(List.of("name", "enabled", "source")), new TreeSet<>(first.keySet()));
        assertEquals("Agility", first.get("name").getAsString());
        assertTrue(first.get("enabled").getAsBoolean());
        assertEquals("builtin", first.get("source").getAsString());
        assertEquals("hub", list.get(1).getAsJsonObject().get("source").getAsString());
        assertFalse(list.get(1).getAsJsonObject().get("enabled").getAsBoolean());
        assertEquals("sideloaded", list.get(2).getAsJsonObject().get("source").getAsString());
    }

    @Test
    public void toggleLinesAppendAndUpdateTheRememberedList() throws Exception
    {
        Path dir = temp.getRoot().toPath();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        PluginLog log = new PluginLog(new GsonBuilder().create(), executor, dir, () -> true);

        log.snapshot(NOW, "Ref", 330, plugins());
        log.toggle(NOW + 1, "Ref", 330, "Block Tracker", true);
        log.toggle(NOW + 2, "Ref", 330, "Agility", false);
        drain(executor);

        List<String> lines = Files.readAllLines(dir.resolve(CollisionLog.fileName(NOW)), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        JsonObject t = parse(lines.get(1));
        assertEquals(new TreeSet<>(List.of("type", "timeMs", "rsn", "world", "name", "enabled")),
            new TreeSet<>(t.keySet()));
        assertEquals("toggle", t.get("type").getAsString());
        assertEquals(NOW + 1, t.get("timeMs").getAsLong());
        assertEquals("Ref", t.get("rsn").getAsString());
        assertEquals(330, t.get("world").getAsInt());
        assertEquals("Block Tracker", t.get("name").getAsString());
        assertTrue(t.get("enabled").getAsBoolean());
        assertFalse(parse(lines.get(2)).get("enabled").getAsBoolean());

        assertEquals(List.of("Block Tracker"), PluginSnapshotter.enabledBanned(log.latest()));
        assertFalse(log.latest().get(0).enabled);
    }

    @Test
    public void logPluginStatsOffWritesNothingButStillRemembers() throws Exception
    {
        Path dir = temp.getRoot().toPath().resolve("plugins");
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        PluginLog log = new PluginLog(new GsonBuilder().create(), executor, dir, () -> false);

        log.snapshot(NOW, "Ref", 330, plugins());
        log.toggle(NOW + 1, "Ref", 330, "Block Tracker", true);
        drain(executor);

        assertFalse(Files.exists(dir));
        assertFalse(log.saving());
        assertEquals(3, log.latest().size());
    }
}
