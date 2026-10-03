package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.GsonBuilder;

/**
 * {@link ReplayWriter}: a single gzipped NDJSON file. Serialising happens on the caller's
 * thread; the write happens on the executor, which here runs inline so the tests can assert
 * on the file right away.
 */
public class ReplayWriterTest
{
    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    /** Runs every task immediately on the caller's thread, like CollisionLogTest's drain does. */
    private static ExecutorService sameThreadExecutor()
    {
        return new AbstractExecutorService()
        {
            @Override
            public void execute(Runnable command)
            {
                command.run();
            }

            @Override
            public void shutdown()
            {
            }

            @Override
            public List<Runnable> shutdownNow()
            {
                return List.of();
            }

            @Override
            public boolean isShutdown()
            {
                return false;
            }

            @Override
            public boolean isTerminated()
            {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit)
            {
                return true;
            }
        };
    }

    private static List<String> gunzipLines(Path file) throws IOException
    {
        List<String> lines = new ArrayList<>();
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(file));
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                lines.add(line);
            }
        }
        return lines;
    }

    private static Map<String, Object> map(String key, Object value)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    @Test
    public void writesGzippedNdjson() throws Exception
    {
        com.google.gson.Gson gson = new GsonBuilder().create();
        ReplayWriter writer = new ReplayWriter(gson, sameThreadExecutor());
        Path file = temp.getRoot().toPath().resolve("a.gz");

        Map<String, Object> first = map("t", "hdr");
        first.put("world", 330);
        Map<String, Object> second = map("t", "tick");
        second.put("tick", 1);

        writer.open(file);
        writer.write(first);
        writer.write(second);
        writer.close();

        List<String> lines = gunzipLines(file);
        assertEquals(2, lines.size());
        assertEquals(gson.toJson(first), lines.get(0));
        assertEquals(gson.toJson(second), lines.get(1));
    }

    @Test
    public void closeFutureCompletesOnlyAfterTheFileHasItsTrailer() throws Exception
    {
        // Holds every task until the test lets it run, so "not done yet" can be observed.
        ConcurrentLinkedQueue<Runnable> held = new ConcurrentLinkedQueue<>();
        ExecutorService gated = new AbstractExecutorService()
        {
            @Override
            public void execute(Runnable command)
            {
                held.add(command);
            }

            @Override
            public void shutdown()
            {
            }

            @Override
            public List<Runnable> shutdownNow()
            {
                return List.of();
            }

            @Override
            public boolean isShutdown()
            {
                return false;
            }

            @Override
            public boolean isTerminated()
            {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit)
            {
                return true;
            }
        };
        com.google.gson.Gson gson = new GsonBuilder().create();
        ReplayWriter writer = new ReplayWriter(gson, gated);
        Path file = temp.getRoot().toPath().resolve("exit.gz");

        writer.open(file);
        for (int i = 0; i < 500; i++)
        {
            writer.write(map("t", "tick" + i));
        }
        Future<?> done = writer.close();
        assertFalse("close has not run yet", done.isDone());

        Thread worker = new Thread(() ->
        {
            Runnable r;
            while ((r = held.poll()) != null)
            {
                r.run();
            }
        });
        worker.start();
        done.get(5, TimeUnit.SECONDS);

        List<String> lines = gunzipLines(file);
        assertEquals(500, lines.size());
        assertEquals(gson.toJson(map("t", "tick499")), lines.get(499));
        worker.join(5000);
    }

    @Test
    public void closeWhenNothingOpenIsSafe()
    {
        ReplayWriter writer = new ReplayWriter(new GsonBuilder().create(), sameThreadExecutor());
        writer.close();
        writer.close();
    }

    @Test
    public void openClosesThePreviousFile() throws Exception
    {
        com.google.gson.Gson gson = new GsonBuilder().create();
        ReplayWriter writer = new ReplayWriter(gson, sameThreadExecutor());
        Path a = temp.getRoot().toPath().resolve("a.gz");
        Path b = temp.getRoot().toPath().resolve("b.gz");

        Map<String, Object> lineA = map("t", "hdr");
        Map<String, Object> lineB = map("t", "tick");

        writer.open(a);
        writer.write(lineA);
        writer.open(b);
        writer.write(lineB);
        writer.close();

        assertEquals(List.of(gson.toJson(lineA)), gunzipLines(a));
        assertEquals(List.of(gson.toJson(lineB)), gunzipLines(b));
    }

    @Test
    public void writeFailureStopsQuietly() throws Exception
    {
        // The "folder" is a file, so creating the parent directory for a file inside it fails.
        Path notADir = temp.newFile("blocked").toPath();
        Path file = notADir.resolve("replay.gz");

        ReplayWriter writer = new ReplayWriter(new GsonBuilder().create(), sameThreadExecutor());
        writer.open(file);

        assertFalse(writer.isOpen());

        // Later writes do nothing and throw nothing.
        writer.write(map("t", "tick"));
        writer.close();

        assertFalse(Files.exists(file));
    }

    @Test
    public void stepsRunInOrderOnAMultiThreadedExecutor() throws Exception
    {
        // A real multi-threaded, non-FIFO-guaranteed executor: ordering has to be the writer's
        // own job (the internal queue + single active drain task), not something borrowed from
        // the executor.
        com.google.gson.Gson gson = new GsonBuilder().create();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        ReplayWriter writer = new ReplayWriter(gson, executor);
        Path file = temp.getRoot().toPath().resolve("ordered.gz");

        List<Map<String, Object>> expected = new ArrayList<>();
        writer.open(file);
        for (int i = 0; i < 500; i++)
        {
            Map<String, Object> line = map("t", "n");
            line.put("n", i);
            expected.add(line);
            writer.write(line);
        }
        writer.close();

        // Wait for every queued step - the open, all 500 writes and the close - to actually run.
        // shutdown() lets already-submitted tasks (the drain task, and any follow-up drain tasks
        // the lost-wake-up recheck scheduled) finish before the pool terminates.
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertFalse(writer.isOpen());

        List<String> lines = gunzipLines(file);
        assertEquals(500, lines.size());
        for (int i = 0; i < 500; i++)
        {
            assertEquals(gson.toJson(expected.get(i)), lines.get(i));
        }
    }

    @Test
    public void aThrowingStepDoesNotWedgeTheWriter() throws Exception
    {
        com.google.gson.Gson gson = new GsonBuilder().create();
        ReplayWriter writer = new ReplayWriter(gson, sameThreadExecutor());

        // Package-private hook (see ReplayWriter.enqueue): injects a step that throws, so the
        // recovery in drain() can be exercised without needing a real IO failure. With the
        // same-thread executor this runs (and drain() fully processes it) synchronously, before
        // this call even returns.
        writer.enqueue(() ->
        {
            throw new RuntimeException("boom");
        });

        Path file = temp.getRoot().toPath().resolve("after-throw.gz");
        writer.open(file);
        writer.write(map("t", "a"));
        writer.write(map("t", "b"));
        writer.write(map("t", "c"));
        writer.close();

        assertEquals(3, gunzipLines(file).size());
    }

    @Test
    public void aFailureOnAFileBoundaryStillFinishesTheNextFile() throws Exception
    {
        // File A fails after B's open is already queued: the failure marks the writer closed, so
        // B's close used to be skipped, leaking B's handle and leaving a header-only gzip.
        java.util.Queue<Runnable> queued = new java.util.ArrayDeque<>();
        ExecutorService manual = new java.util.concurrent.AbstractExecutorService()
        {
            public void execute(Runnable r) { queued.add(r); }
            public void shutdown() { }
            public List<Runnable> shutdownNow() { return List.of(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long t, java.util.concurrent.TimeUnit u) { return true; }
        };
        ReplayWriter writer = new ReplayWriter(new GsonBuilder().create(), manual);
        Path a = temp.getRoot().toPath().resolve("a.rflr.gz");
        Path b = temp.getRoot().toPath().resolve("b.rflr.gz");
        writer.open(a);
        writer.enqueue(() ->
        {
            throw new IllegalStateException("boom");
        });
        writer.open(b);
        while (!queued.isEmpty())
        {
            queued.poll().run();
        }
        writer.close();
        while (!queued.isEmpty())
        {
            queued.poll().run();
        }
        // B must be a complete gzip (trailer written), even though its lines were dropped.
        gunzipLines(b);
    }
}
