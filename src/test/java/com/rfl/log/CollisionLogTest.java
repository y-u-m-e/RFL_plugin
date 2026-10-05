package com.rfl.log;

import com.rfl.Fixtures;
import com.rfl.contact.Collision;
import sh.yumekui.toolkit.io.DailyJsonlAppender;

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
    private static final long HALF_SECOND = 500;
    private static final int TICK = 10;
    /** A house tile: world x/y are template coordinates, scene x/y the tile in the loaded house. */
    private static final int TEMPLATE_X = 1890;
    private static final int TEMPLATE_Y = 5730;
    private static final int SCENE_X = 14;
    private static final int SCENE_Y = 44;
    private static final int TRIANGLES = 12;

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private static Collision collision(String a, long endMs)
    {
        return Fixtures.collision(a, "Zed").at(endMs - HALF_SECOND, HALF_SECOND).ticks(TICK, TICK + 1)
            .tile(TEMPLATE_X, TEMPLATE_Y, 0, SCENE_X, SCENE_Y).triangles(TRIANGLES).build();
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
        Fixtures.drain(executor);

        Path file = dir.resolve(DailyJsonlAppender.fileName(END));
        assertTrue(file.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl"));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());

        JsonObject o = new JsonParser().parse(lines.get(0)).getAsJsonObject();
        assertEquals(new TreeSet<>(List.of("type", "a", "b", "ball", "startMs", "endMs", "startTick", "endTick",
            "world", "x", "y", "plane", "sx", "sy", "maxTriangles")), keys(o));
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
        assertEquals(14, o.get("sx").getAsInt());
        assertEquals(44, o.get("sy").getAsInt());
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
        log.record(Fixtures.incomplete("Amy").contacts("Zed", "Bo").at(END + 5, 42).tile(1891, 5731, 0, 14, 44).catchCycle(36187).build());
        Fixtures.drain(executor);

        List<String> lines = Files.readAllLines(dir.resolve(DailyJsonlAppender.fileName(END)), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        JsonObject i = new JsonParser().parse(lines.get(1)).getAsJsonObject();
        assertEquals(new TreeSet<>(List.of("type", "receiver", "contacts", "timeMs", "tick", "world", "x", "y",
            "plane", "sx", "sy", "catchCyc")), keys(i));
        assertEquals(36187, i.get("catchCyc").getAsInt());
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
        assertEquals(14, i.get("sx").getAsInt());
        assertEquals(44, i.get("sy").getAsInt());
    }

    @Test
    public void saveCollisionsOffWritesNothing() throws Exception
    {
        Path dir = temp.getRoot().toPath().resolve("collisions");
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CollisionLog log = new CollisionLog(new GsonBuilder().create(), executor, dir, () -> false);

        log.record(collision("Amy", END));
        log.record(Fixtures.incomplete("Amy").contacts("Zed").at(END, 1).build());
        Fixtures.drain(executor);

        assertFalse(Files.exists(dir));
    }

    @Test
    public void ioErrorsAreSwallowed() throws Exception
    {
        // The "folder" is a file, so creating it and writing under it both fail.
        Path notADir = temp.newFile("blocked").toPath();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CollisionLog log = new CollisionLog(new GsonBuilder().create(), executor, notADir, () -> true);
        log.record(collision("Amy", END));
        Fixtures.drain(executor);
        assertFalse(Files.isDirectory(notADir));
        assertEquals("nothing reached disk", 0L, log.lastSavedAtMs());
    }

    @Test
    public void lastSavedAtIsSetOnlyWhenALineIsWritten() throws Exception
    {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CollisionLog off = new CollisionLog(new GsonBuilder().create(), executor,
            temp.getRoot().toPath().resolve("off"), () -> false);
        CollisionLog on = new CollisionLog(new GsonBuilder().create(), executor,
            temp.getRoot().toPath().resolve("on"), () -> true);
        assertEquals(0L, on.lastSavedAtMs());

        long before = System.currentTimeMillis();
        off.record(collision("Amy", END));
        on.record(collision("Amy", END));
        Fixtures.drain(executor);

        assertEquals(0L, off.lastSavedAtMs());
        assertTrue(on.lastSavedAtMs() >= before);
    }
}
