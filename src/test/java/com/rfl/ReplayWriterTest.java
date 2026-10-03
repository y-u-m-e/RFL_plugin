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
import java.util.concurrent.ExecutorService;
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
}
