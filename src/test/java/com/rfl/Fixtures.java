package com.rfl;

import static org.junit.Assert.assertTrue;

import com.rfl.contact.Collision;
import com.rfl.incomplete.Incomplete;
import sh.yumekui.toolkit.geom.TriangleMesh;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Shared test data, so each test states only what it is about: collision and incomplete builders
 * with plain defaults, the crossing-triangle shapes the contact tests use, and small helpers for
 * executors and gzip files.
 */
public final class Fixtures
{
    /** The world every fixture event happens on. */
    public static final int WORLD = 330;

    /** A triangle in the plane x = 40. */
    public static final double[][] WALL = {{40, -20, 40}, {40, 20, 40}, {40, 0, 80}};
    /** A triangle in the plane y = 0 that crosses {@link #WALL}. */
    public static final double[][] THROUGH = {{30, 0, 50}, {50, 0, 50}, {40, 0, 70}};
    /** A triangle in the plane y = 10 that also crosses {@link #WALL}, but not {@link #THROUGH}. */
    public static final double[][] THROUGH_2 = {{30, 10, 45}, {50, 10, 45}, {40, 10, 55}};
    /** Inside {@link #WALL}'s bounds but clear of it (WALL spans z 40-44 at y = 18): bounds overlap, nothing touches. */
    public static final double[][] NEAR = {{30, 18, 70}, {50, 18, 70}, {40, 18, 80}};
    /** Far clear of {@link #WALL}'s bounds. */
    public static final double[][] FAR = {{300, 0, 50}, {320, 0, 50}, {310, 0, 70}};

    /** Long enough for a test executor to finish its queued work. */
    private static final long DRAIN_SECONDS = 5;

    private Fixtures()
    {
    }

    /** A mesh of the given triangles, each corner its own vertex, shifted {@code dx} along x. */
    public static TriangleMesh mesh(double dx, double[][]... triangles)
    {
        int vertices = triangles.length * 3;
        float[] x = new float[vertices];
        float[] y = new float[vertices];
        float[] z = new float[vertices];
        int[] faces = new int[vertices];
        for (int t = 0; t < triangles.length; t++)
        {
            for (int corner = 0; corner < 3; corner++)
            {
                int v = t * 3 + corner;
                x[v] = (float) (triangles[t][corner][0] + dx);
                y[v] = (float) triangles[t][corner][1];
                z[v] = (float) triangles[t][corner][2];
                faces[v] = v;
            }
        }
        return new TriangleMesh(x, y, z, faces);
    }

    /** {@link #mesh(double, double[][]...)} with no shift. */
    public static TriangleMesh mesh(double[][]... triangles)
    {
        return mesh(0, triangles);
    }

    /** Shuts the executor down and waits for its queued work. */
    public static void drain(ExecutorService executor) throws InterruptedException
    {
        executor.shutdown();
        assertTrue("executor finished", executor.awaitTermination(DRAIN_SECONDS, TimeUnit.SECONDS));
    }

    /** Every line of a gzipped text file. */
    public static List<String> gzipLines(Path file) throws IOException
    {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                lines.add(line);
            }
        }
        return lines;
    }

    /** A collision between {@code a} and {@code b}; set only what the test is about. */
    public static CollisionBuilder collision(String a, String b)
    {
        return new CollisionBuilder(a, b);
    }

    /** An incomplete caught by {@code receiver}; set only what the test is about. */
    public static IncompleteBuilder incomplete(String receiver)
    {
        return new IncompleteBuilder(receiver);
    }

    /** Builds a {@link Collision}: one second long, {@code b} holding the ball, unknown tile. */
    public static final class CollisionBuilder
    {
        private final String a;
        private final String b;
        private List<String> holders;
        private long startMs;
        private long endMs = 1000;
        private int startTick;
        private int endTick = 1;
        private int worldX;
        private int worldY;
        private int plane;
        private int sceneX;
        private int sceneY;
        private int triangles = 1;

        private CollisionBuilder(String a, String b)
        {
            this.a = a;
            this.b = b;
            this.holders = List.of(b);
        }

        public CollisionBuilder holders(String... names)
        {
            holders = Arrays.asList(names);
            return this;
        }

        /** Starts at {@code startMs} and ends {@code durationMs} later. */
        public CollisionBuilder at(long startMs, long durationMs)
        {
            this.startMs = startMs;
            this.endMs = startMs + durationMs;
            return this;
        }

        public CollisionBuilder ticks(int startTick, int endTick)
        {
            this.startTick = startTick;
            this.endTick = endTick;
            return this;
        }

        public CollisionBuilder tile(int worldX, int worldY, int plane, int sceneX, int sceneY)
        {
            this.worldX = worldX;
            this.worldY = worldY;
            this.plane = plane;
            this.sceneX = sceneX;
            this.sceneY = sceneY;
            return this;
        }

        public CollisionBuilder triangles(int triangles)
        {
            this.triangles = triangles;
            return this;
        }

        public Collision build()
        {
            return new Collision(a, b, holders, startMs, endMs, startTick, endTick, WORLD, worldX, worldY, plane, sceneX,
                sceneY, triangles);
        }
    }

    /** Builds an {@link Incomplete}: no contacts, unknown tile and catch cycle. */
    public static final class IncompleteBuilder
    {
        private final String receiver;
        private List<String> contacts = List.of();
        private long timeMs;
        private int tick;
        private int worldX;
        private int worldY;
        private int plane;
        private int sceneX;
        private int sceneY;
        /** -1: the catch cycle wasn't known, as in older saved lines. */
        private int catchCycle = -1;

        private IncompleteBuilder(String receiver)
        {
            this.receiver = receiver;
        }

        public IncompleteBuilder contacts(String... names)
        {
            contacts = Arrays.asList(names);
            return this;
        }

        public IncompleteBuilder at(long timeMs, int tick)
        {
            this.timeMs = timeMs;
            this.tick = tick;
            return this;
        }

        public IncompleteBuilder tile(int worldX, int worldY, int plane, int sceneX, int sceneY)
        {
            this.worldX = worldX;
            this.worldY = worldY;
            this.plane = plane;
            this.sceneX = sceneX;
            this.sceneY = sceneY;
            return this;
        }

        public IncompleteBuilder catchCycle(int catchCycle)
        {
            this.catchCycle = catchCycle;
            return this;
        }

        public Incomplete build()
        {
            return new Incomplete(receiver, contacts, timeMs, tick, WORLD, worldX, worldY, plane, sceneX, sceneY,
                catchCycle);
        }
    }
}
