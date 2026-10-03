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

/**
 * {@link CollisionLog}: the plugin's only output, a local day file. Built here from nothing but Gson,
 * an executor and a folder (no HTTP client, no server), which is all it needs in game too.
 */
public class CollisionLogTest
{
    private static final long END = 1_790_000_000_000L;

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private static Collision collision(String a, long endMs)
    {
        return new Collision(a, "Zed", List.of("Zed"), endMs - 500, endMs, 10, 11, 330, 1890, 5730, 0, 12);
    }

    private static void drain(ScheduledExecutorService executor) throws InterruptedException
    {
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static Set<String> keys(JsonObject o)
    {
        return new TreeSet<>(o.keySet());
    }

    @Test
    public void appendsOneCollisionLinePerCollisionToTheLocalDayFileWithNoNetwork() throws Exception
    {
        Path dir = temp.getRoot().toPath().resolve("rfl").resolve("collisions");
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CollisionLog log = new CollisionLog(new GsonBuilder().create(), executor, dir, () -> true);

        log.record(collision("Amy", END));
        log.record(collision("Bo", END + 1));
        drain(executor);

        Path file = dir.resolve(CollisionLog.fileName(END));
        assertTrue(file.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl"));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());

        JsonObject o = new JsonParser().parse(lines.get(0)).getAsJsonObject();
        assertEquals(new TreeSet<>(List.of("type", "a", "b", "ball", "startMs", "endMs", "startTick", "endTick",
            "world", "x", "y", "plane", "maxTriangles")), keys(o));
        assertEquals("collision", o.get("type").getAsString());
        assertEquals("Amy", o.get("a").getAsString());
        assertEquals("Zed", o.get("b").getAsString());
        assertEquals("Zed", o.getAsJsonArray("ball").get(0).getAsString());
        assertEquals(END - 500, o.get("startMs").getAsLong());
        assertEquals(END, o.get("endMs").getAsLong());
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
    public void appendsIncompletesToTheSameDayFileAsTheirOwnLineType() throws Exception
    {
        Path dir = temp.getRoot().toPath();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CollisionLog log = new CollisionLog(new GsonBuilder().create(), executor, dir, () -> true);

        log.record(collision("Amy", END));
        log.record(new CollisionLog.Incomplete("Amy", List.of("Zed", "Bo"), END + 5, 42, 330, 1891, 5731, 0));
        drain(executor);

        List<String> lines = Files.readAllLines(dir.resolve(CollisionLog.fileName(END)), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        JsonObject i = new JsonParser().parse(lines.get(1)).getAsJsonObject();
        assertEquals(new TreeSet<>(List.of("type", "receiver", "contacts", "timeMs", "tick", "world", "x", "y",
            "plane")), keys(i));
        assertEquals("incomplete", i.get("type").getAsString());
        assertEquals("Amy", i.get("receiver").getAsString());
        assertEquals("Zed", i.getAsJsonArray("contacts").get(0).getAsString());
        assertEquals("Bo", i.getAsJsonArray("contacts").get(1).getAsString());
        assertEquals(END + 5, i.get("timeMs").getAsLong());
        assertEquals(42, i.get("tick").getAsInt());
        assertEquals(330, i.get("world").getAsInt());
        assertEquals(1891, i.get("x").getAsInt());
        assertEquals(5731, i.get("y").getAsInt());
        assertEquals(0, i.get("plane").getAsInt());
        // Incompletes are not collisions: the debug panel list only shows collisions.
        assertEquals(1, log.recent().size());
    }

    @Test
    public void saveCollisionsOffWritesNothingButStillListsRecentCollisions() throws Exception
    {
        Path dir = temp.getRoot().toPath().resolve("collisions");
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CollisionLog log = new CollisionLog(new GsonBuilder().create(), executor, dir, () -> false);

        log.record(collision("Amy", END));
        log.record(new CollisionLog.Incomplete("Amy", List.of("Zed"), END, 1, 330, 0, 0, 0));
        drain(executor);

        assertFalse(Files.exists(dir));
        assertEquals(1, log.recent().size());
    }

    @Test
    public void keepsTheLatestTwentyNewestFirst() throws Exception
    {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CollisionLog log = new CollisionLog(new GsonBuilder().create(), executor, temp.getRoot().toPath(), () -> true);
        for (int i = 0; i < 25; i++)
        {
            log.record(collision("P" + i, END + i));
        }
        drain(executor);

        List<Collision> recent = log.recent();
        assertEquals(CollisionLog.RECENT, recent.size());
        assertEquals("P24", recent.get(0).a);
        assertEquals("P5", recent.get(19).a);
        String row = CollisionLog.row(recent.get(0));
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
        CollisionLog log = new CollisionLog(new GsonBuilder().create(), executor, notADir, () -> true);
        log.record(collision("Amy", END));
        log.ensureDir();
        drain(executor);
        assertFalse(Files.isDirectory(notADir));
        assertEquals(1, log.recent().size());
    }
}
