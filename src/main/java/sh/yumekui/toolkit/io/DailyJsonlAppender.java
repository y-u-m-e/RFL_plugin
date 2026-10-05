package sh.yumekui.toolkit.io;

import sh.yumekui.toolkit.concurrent.SerialQueue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

/**
 * Appends JSON lines to one file per local day ({@code YYYY-MM-DD.jsonl}) in a folder, creating the
 * folder as needed. Each line is encoded and written off the caller's thread, through a
 * {@link SerialQueue}, so lines land in the order they were appended whatever the executor does.
 * An IO error is logged and that line dropped; nothing is retried.
 *
 * <p>Threads: {@link #append} from any thread; {@link #lastSavedAtMs} from any thread.
 */
@Slf4j
public final class DailyJsonlAppender
{
    private final Path dir;
    private final SerialQueue queue;
    /** Written on the executor after each successful append. */
    private volatile long lastSavedAtMs;

    public DailyJsonlAppender(Path dir, Executor executor)
    {
        this.dir = dir;
        this.queue = new SerialQueue(executor, e -> log.warn("Daily JSONL: append failed", e));
    }

    /**
     * Queues one line for the day file of {@code epochMs}. {@code json} is called on the executor,
     * so the caller's thread never pays for the encoding.
     */
    public void append(Supplier<String> json, long epochMs)
    {
        queue.enqueue(() -> write(json.get(), epochMs));
    }

    private void write(String json, long epochMs)
    {
        Path file = dayFile(epochMs);
        try
        {
            Files.createDirectories(dir);
            Files.write(file, (json + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            lastSavedAtMs = System.currentTimeMillis();
        }
        catch (IOException e)
        {
            log.warn("Daily JSONL: can't write {}", file, e);
        }
    }

    /** The day file for the local date of an epoch ms, which may not exist yet. */
    public Path dayFile(long epochMs)
    {
        return dir.resolve(fileName(epochMs));
    }

    /** {@code YYYY-MM-DD.jsonl} for the local date of an epoch ms. */
    public static String fileName(long epochMs)
    {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate() + ".jsonl";
    }

    /** Epoch ms the last line reached disk, 0 before any; for the panel's "Saved" tick. */
    public long lastSavedAtMs()
    {
        return lastSavedAtMs;
    }
}
