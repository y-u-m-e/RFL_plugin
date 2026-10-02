package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ObserverLogTest
{
    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private static ObservedCollision collision(String a, long endMs)
    {
        return new ObservedCollision(a, "Zed", List.of("Zed"), endMs - 500, endMs, 10, 11, 330, 1890, 5730, 0, 12);
    }

    @Test
    public void appendsOneJsonLinePerCollisionToTheLocalDayFile() throws Exception
    {
        Path dir = temp.getRoot().toPath().resolve("rfl").resolve("observer");
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        ObserverLog log = new ObserverLog(new GsonBuilder().create(), executor, dir);
        long end = 1_790_000_000_000L;

        log.record(collision("Amy", end));
        log.record(collision("Bo", end + 1));
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        Path file = dir.resolve(ObserverLog.fileName(end));
        assertTrue(file.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl"));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());

        JsonObject o = new JsonParser().parse(lines.get(0)).getAsJsonObject();
        Set<String> keys = new TreeSet<>(o.keySet());
        assertEquals(new TreeSet<>(List.of("a", "b", "ball", "startMs", "endMs", "startTick", "endTick", "world",
            "x", "y", "plane", "maxTriangles")), keys);
        assertEquals("Amy", o.get("a").getAsString());
        assertEquals("Zed", o.get("b").getAsString());
        assertEquals("Zed", o.getAsJsonArray("ball").get(0).getAsString());
        assertEquals(end - 500, o.get("startMs").getAsLong());
        assertEquals(end, o.get("endMs").getAsLong());
        assertEquals(10, o.get("startTick").getAsInt());
        assertEquals(11, o.get("endTick").getAsInt());
        assertEquals(330, o.get("world").getAsInt());
        assertEquals(1890, o.get("x").getAsInt());
        assertEquals(5730, o.get("y").getAsInt());
        assertEquals(0, o.get("plane").getAsInt());
        assertEquals(12, o.get("maxTriangles").getAsInt());
        assertEquals("Bo", new JsonParser().parse(lines.get(1)).getAsJsonObject().get("a").getAsString());
    }

    @Test
    public void keepsTheLatestTwentyNewestFirst() throws Exception
    {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        ObserverLog log = new ObserverLog(new GsonBuilder().create(), executor, temp.getRoot().toPath());
        int[] changes = {0};
        log.setOnChange(() -> changes[0]++);
        for (int i = 0; i < 25; i++)
        {
            log.record(collision("P" + i, 1_790_000_000_000L + i));
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        List<ObservedCollision> recent = log.recent();
        assertEquals(ObserverLog.RECENT, recent.size());
        assertEquals("P24", recent.get(0).a);
        assertEquals("P5", recent.get(19).a);
        assertEquals(25, changes[0]);
        String row = ObserverLog.row(recent.get(0));
        assertTrue(row.contains("P24 ↔ Zed"));
        assertTrue(row.contains("ball: Zed"));
        assertTrue(row.contains("overlap: 12"));
    }

    @Test
    public void ioErrorsAreSwallowed() throws Exception
    {
        // The "folder" is a file, so creating it and writing under it both fail.
        Path notADir = temp.newFile("blocked").toPath();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        ObserverLog log = new ObserverLog(new GsonBuilder().create(), executor, notADir);
        log.record(collision("Amy", 1_790_000_000_000L));
        log.ensureDir();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertFalse(Files.isDirectory(notADir));
        assertEquals(1, log.recent().size());
    }
}
