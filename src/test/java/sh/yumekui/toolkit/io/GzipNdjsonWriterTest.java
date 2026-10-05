package sh.yumekui.toolkit.io;

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
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * {@link GzipNdjsonWriter}: a single gzipped NDJSON file. Serialising happens on the caller's
 * thread; the write happens on the executor, which here runs inline so the tests can assert
 * on the file right away.
 */
public class GzipNdjsonWriterTest
{
    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    /** The file names' fixed ending; a taken name is numbered before it. */
    private static final String SUFFIX = ".ndjson.gz";

    /** A writer whose steps run on {@code executor}. */
    private static GzipNdjsonWriter writer(Gson gson, ExecutorService executor)
    {
        return writer(gson, executor, System::currentTimeMillis);
    }

    /** A writer whose steps run on {@code executor}, with a clock for the periodic sync flush. */
    private static GzipNdjsonWriter writer(Gson gson, ExecutorService executor, LongSupplier clock)
    {
        return new GzipNdjsonWriter(gson, SUFFIX, () -> executor, clock, GzipNdjsonWriter.MAX_PENDING_BYTES);
    }

    /** Runs every task immediately on the caller's thread, so a test can read the file at once. */
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
        GzipNdjsonWriter writer = writer(gson, sameThreadExecutor());
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
    public void deferredLinesKeepTheirPlace() throws Exception
    {
        // Model lines are serialised on the drain task, but still land between their neighbours.
        com.google.gson.Gson gson = new GsonBuilder().create();
        GzipNdjsonWriter writer = writer(gson, sameThreadExecutor());
        Path file = temp.getRoot().toPath().resolve("d.gz");
        Map<String, Object> model = map("t", "model");
        model.put("v", new int[] { 1, 2, 3 });

        writer.open(file);
        writer.write(map("t", "hdr"));
        writer.writeDeferred(model);
        writer.write(map("t", "pm"));
        writer.close();

        List<String> lines = gunzipLines(file);
        assertEquals(List.of("{\"t\":\"hdr\"}", "{\"t\":\"model\",\"v\":[1,2,3]}", "{\"t\":\"pm\"}"), lines);
        assertEquals(1, writer.deferredLines());
        assertEquals(lines.get(1).length() + 1, writer.deferredBytes());
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
        GzipNdjsonWriter writer = writer(gson, gated);
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
        GzipNdjsonWriter writer = writer(new GsonBuilder().create(), sameThreadExecutor());
        writer.close();
        writer.close();
    }

    @Test
    public void openClosesThePreviousFile() throws Exception
    {
        com.google.gson.Gson gson = new GsonBuilder().create();
        GzipNdjsonWriter writer = writer(gson, sameThreadExecutor());
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
        Path file = notADir.resolve("file.ndjson.gz");

        GzipNdjsonWriter writer = writer(new GsonBuilder().create(), sameThreadExecutor());
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
        GzipNdjsonWriter writer = writer(gson, executor);
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
        GzipNdjsonWriter writer = writer(gson, sameThreadExecutor());

        // Package-private hook (see GzipNdjsonWriter.enqueue): injects a step that throws, so the
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
        GzipNdjsonWriter writer = writer(new GsonBuilder().create(), manual);
        Path a = temp.getRoot().toPath().resolve("a.ndjson.gz");
        Path b = temp.getRoot().toPath().resolve("b.ndjson.gz");
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

    /** Holds every executor task until {@link #runHeld}, so "not saved yet" can be observed. */
    private static ExecutorService held(ConcurrentLinkedQueue<Runnable> held)
    {
        return new AbstractExecutorService()
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
    }

    private static void runHeld(ConcurrentLinkedQueue<Runnable> held)
    {
        Runnable r;
        while ((r = held.poll()) != null)
        {
            r.run();
        }
    }

    @Test
    public void progressTracksQueuedAndWritten() throws Exception
    {
        ConcurrentLinkedQueue<Runnable> held = new ConcurrentLinkedQueue<>();
        GzipNdjsonWriter writer = writer(new GsonBuilder().create(), held(held));
        Path file = temp.getRoot().toPath().resolve("progress.gz");

        assertEquals(WriterState.IDLE, writer.state());
        writer.open(file);
        assertEquals(WriterState.WRITING, writer.state());
        // Equal-sized lines, with a probe exactly halfway through the queue.
        for (int i = 0; i < 100; i++)
        {
            writer.write(map("t", String.format("tick%04d", i)));
        }
        List<Double> seen = new ArrayList<>();
        writer.enqueue(() -> seen.add(writer.progress()));
        for (int i = 100; i < 200; i++)
        {
            writer.write(map("t", String.format("tick%04d", i)));
        }
        writer.close();
        assertEquals(WriterState.SAVING, writer.state());
        assertEquals(0.0, writer.progress(), 1e-9);

        runHeld(held);

        assertEquals(1, seen.size());
        assertEquals(0.5, seen.get(0), 0.01);
    }

    @Test
    public void savingProgressReachesDone() throws Exception
    {
        ConcurrentLinkedQueue<Runnable> held = new ConcurrentLinkedQueue<>();
        GzipNdjsonWriter writer = writer(new GsonBuilder().create(), held(held));
        Path file = temp.getRoot().toPath().resolve("done.gz");

        writer.open(file);
        for (int i = 0; i < 300; i++)
        {
            writer.write(map("t", "tick" + i));
        }
        writer.writeDeferred(map("t", "model"));
        Future<?> done = writer.close();
        assertEquals(WriterState.SAVING, writer.state());
        assertTrue(writer.progress() < 1.0);

        runHeld(held);
        done.get(5, TimeUnit.SECONDS);

        assertEquals(WriterState.SAVED, writer.state());
        assertEquals(1.0, writer.progress(), 1e-9);
        // The saved size is the finished file on disk, gzip trailer included.
        assertEquals(Files.size(file), writer.savedBytes());
        assertTrue(writer.savedAtMs() > 0);
        assertEquals(301, gunzipLines(file).size());

        // A new file starts over.
        writer.open(temp.getRoot().toPath().resolve("next.gz"));
        runHeld(held);
        assertEquals(WriterState.WRITING, writer.state());
        assertEquals(0.0, writer.progress(), 1e-9);
    }

    @Test
    public void failureSurfacesAsErrorWithAReason() throws Exception
    {
        Path notADir = temp.newFile("blocked2").toPath();
        GzipNdjsonWriter writer = writer(new GsonBuilder().create(), sameThreadExecutor());

        writer.open(notADir.resolve("file.ndjson.gz"));
        writer.close();

        assertEquals(WriterState.ERROR, writer.state());
        assertTrue(writer.error() != null && !writer.error().isEmpty());
        assertFalse("the reason is not just a path", writer.error().contains(notADir.toString()));

        // The next good file clears the error.
        writer.open(temp.getRoot().toPath().resolve("ok.gz"));
        assertEquals(WriterState.WRITING, writer.state());
        assertEquals(null, writer.error());
    }

    @Test
    public void fileBytesCountsTheCompressedFile() throws Exception
    {
        GzipNdjsonWriter writer = writer(new GsonBuilder().create(), sameThreadExecutor());
        Path file = temp.getRoot().toPath().resolve("size.gz");
        writer.open(file);
        assertTrue("gzip header", writer.fileBytes() > 0);
        writer.write(map("t", "hdr"));
        writer.close();
        assertEquals(Files.size(file), writer.fileBytes());
    }

    @Test
    public void deferredNonModelLinesStayOutOfTheModelCounts() throws Exception
    {
        com.google.gson.Gson gson = new GsonBuilder().create();
        GzipNdjsonWriter writer = writer(gson, sameThreadExecutor());
        Path file = temp.getRoot().toPath().resolve("p.gz");
        Map<String, Object> pitch = map("t", "pitch");
        pitch.put("heights", new int[] { 1, 2 });

        writer.open(file);
        writer.write(map("t", "hdr"));
        writer.writeDeferred(pitch, false);
        writer.write(map("t", "f"));
        writer.close();

        assertEquals(List.of("{\"t\":\"hdr\"}", "{\"t\":\"pitch\",\"heights\":[1,2]}", "{\"t\":\"f\"}"),
            gunzipLines(file));
        assertEquals(0, writer.deferredLines());
        assertEquals(0, writer.deferredBytes());
    }

    @Test
    public void aTakenNameGetsTheNextNumberInsteadOfBeingOverwritten() throws Exception
    {
        GzipNdjsonWriter writer = writer(new GsonBuilder().create(), sameThreadExecutor());
        Path dir = temp.getRoot().toPath();
        Path file = dir.resolve("game.ndjson.gz");
        Files.write(file, new byte[] { 1, 2, 3 });
        Files.write(dir.resolve("game-2.ndjson.gz"), new byte[] { 4 });

        writer.open(file);
        writer.write(map("t", "hdr"));
        writer.close().get(5, TimeUnit.SECONDS);

        assertEquals("the old file is untouched", 3, Files.size(file));
        Path numbered = dir.resolve("game-3.ndjson.gz");
        assertEquals(List.of("{\"t\":\"hdr\"}"), gunzipLines(numbered));
        assertEquals("game-3.ndjson.gz", writer.fileName());
        assertEquals(numbered, writer.created());
    }

    /** A realistic f line: 20 players' pose tuples, about 0.8 KB of JSON. */
    private static Map<String, Object> frameLine(int cycle)
    {
        Map<String, Object> m = map("t", "f");
        m.put("cyc", cycle);
        List<int[]> rows = new ArrayList<>();
        for (int p = 0; p < 20; p++)
        {
            rows.add(new int[] { p, 6400 + (cycle * 7 + p * 131) % 900, 6600 + (cycle * 3 + p * 71) % 700,
                (cycle * 11 + p) % 2048, 808 + p % 3, cycle % 12, 819, cycle % 20 });
        }
        m.put("p", rows);
        return m;
    }

    /** Complete lines that inflate from a gzip cut off anywhere: bytes until the stream breaks. */
    private static List<String> inflateTruncated(byte[] bytes)
    {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes)))
        {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
            {
                out.write(buf, 0, n);
            }
        }
        catch (IOException e)
        {
            // The cut-off tail: expected after a crash.
        }
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>();
        int start = 0;
        int end;
        while ((end = text.indexOf('\n', start)) >= 0)
        {
            lines.add(text.substring(start, end));
            start = end + 1;
        }
        return lines;
    }

    @Test
    public void aCrashLosesOnlyWhatCameAfterTheLastSyncFlush() throws Exception
    {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        GzipNdjsonWriter writer = writer(new GsonBuilder().create(), sameThreadExecutor(), now::get);
        Path file = temp.getRoot().toPath().resolve("crash.ndjson.gz");
        writer.open(file);
        // 5 s of 50 Hz frames: sync flushes at 2 s and 4 s.
        int flushedThrough = -1;
        for (int i = 0; i < 250; i++)
        {
            writer.write(frameLine(i));
            if (now.get() - 1_000_000L == 4_000L && flushedThrough < 0)
            {
                flushedThrough = i;
            }
            now.addAndGet(20);
        }
        // The client is killed here: no close, no trailer. Whatever is on disk is all there is.
        byte[] crashed = Files.readAllBytes(file);
        List<String> recovered = inflateTruncated(crashed);
        assertTrue("recovered " + recovered.size() + ", flushed through " + flushedThrough,
            recovered.size() >= flushedThrough + 1);
        for (int i = 0; i < recovered.size(); i++)
        {
            com.google.gson.JsonObject o = new com.google.gson.JsonParser().parse(recovered.get(i)).getAsJsonObject();
            assertEquals(i, o.get("cyc").getAsInt());
        }

        // A normal stop still ends with a proper trailer: the whole file inflates.
        writer.close().get(5, TimeUnit.SECONDS);
        assertEquals(250, gunzipLines(file).size());
    }

    @Test
    public void syncFlushEveryTwoSecondsCostsWellUnderOnePercent() throws Exception
    {
        long[] sizes = new long[2];
        for (int run = 0; run < 2; run++)
        {
            java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(0L);
            boolean flushing = run == 1;
            GzipNdjsonWriter writer = writer(new GsonBuilder().create(), sameThreadExecutor(), () -> flushing ? now.get() : 0L);
            Path file = temp.getRoot().toPath().resolve("size" + run + ".ndjson.gz");
            writer.open(file);
            // 10 minutes at 50 Hz, with a tick line every 0.6 s.
            for (int i = 0; i < 30_000; i++)
            {
                writer.write(frameLine(i));
                if (i % 30 == 0)
                {
                    Map<String, Object> tick = map("t", "tick");
                    tick.put("tick", i / 30);
                    writer.write(tick);
                }
                now.addAndGet(20);
            }
            writer.close().get(5, TimeUnit.SECONDS);
            sizes[run] = Files.size(file);
        }
        double overhead = (sizes[1] - sizes[0]) * 100.0 / sizes[0];
        System.out.printf("Sync flush overhead: %d -> %d bytes, %.3f%%%n", sizes[0], sizes[1], overhead);
        assertTrue("overhead " + overhead + "%", overhead < 1.0);
    }

    @Test
    public void aDiskThatFallsBehindFailsTheFileCleanlyInsteadOfQueueingForever() throws Exception
    {
        com.google.gson.Gson gson = new GsonBuilder().create();
        ConcurrentLinkedQueue<Runnable> stalledDisk = new ConcurrentLinkedQueue<>();
        ExecutorService executor = held(stalledDisk);
        // Room for exactly three equal-sized lines waiting at once.
        long oneLine = gson.toJson(map("t", "tick0")).length() + 1;
        GzipNdjsonWriter writer = new GzipNdjsonWriter(gson, SUFFIX, () -> executor, System::currentTimeMillis, 3 * oneLine);
        Path file = temp.getRoot().toPath().resolve("slow.gz");

        writer.open(file);
        for (int i = 0; i < 3; i++)
        {
            writer.write(map("t", "tick" + i));
        }
        assertTrue("three lines fit under the cap", writer.isOpen());
        writer.write(map("t", "tick3"));

        assertEquals(WriterState.ERROR, writer.state());
        assertEquals(GzipNdjsonWriter.DISK_TOO_SLOW, writer.error());
        assertFalse(writer.isOpen());
        writer.write(map("t", "tick4"));

        // The disk catches up: what was queued before the cap is written, then the gzip trailer.
        runHeld(stalledDisk);
        assertEquals(List.of(gson.toJson(map("t", "tick0")), gson.toJson(map("t", "tick1")),
            gson.toJson(map("t", "tick2"))), gunzipLines(file));
    }

    @Test
    public void shutdownEndsTheWriterThreadAfterTheQueueAndTheNextFileStartsANewOne() throws Exception
    {
        com.google.gson.Gson gson = new GsonBuilder().create();
        List<ExecutorService> made = new ArrayList<>();
        GzipNdjsonWriter writer = new GzipNdjsonWriter(gson, SUFFIX, () ->
        {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            made.add(executor);
            return executor;
        }, System::currentTimeMillis, GzipNdjsonWriter.MAX_PENDING_BYTES);
        Path first = temp.getRoot().toPath().resolve("first.gz");
        Path second = temp.getRoot().toPath().resolve("second.gz");

        writer.open(first);
        writer.write(map("t", "tick"));
        writer.close();
        writer.shutdown().get(5, TimeUnit.SECONDS);
        assertEquals(1, made.size());
        assertTrue("the thread ends once the queue is done", made.get(0).awaitTermination(5, TimeUnit.SECONDS));
        assertEquals("everything queued before the shutdown was written", 1, gunzipLines(first).size());

        writer.open(second);
        writer.write(map("t", "tick"));
        writer.close().get(5, TimeUnit.SECONDS);
        assertEquals("a new thread for the next file", 2, made.size());
        assertEquals(1, gunzipLines(second).size());
        writer.shutdown().get(5, TimeUnit.SECONDS);
    }
}
