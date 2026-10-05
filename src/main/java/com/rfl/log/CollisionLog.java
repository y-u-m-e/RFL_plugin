package com.rfl.log;

import com.rfl.RflConfig;
import com.rfl.contact.Collision;
import com.rfl.incomplete.Incomplete;
import sh.yumekui.toolkit.io.DailyJsonlAppender;

import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;

import net.runelite.client.RuneLite;

/**
 * The plugin's output, on this computer only: appends one JSON line per finished {@link Collision}
 * ({@code "type":"collision"}) and per {@link Incomplete} ({@code "type":"incomplete"}) to
 * {@code RUNELITE_DIR/rfl/collisions/YYYY-MM-DD.jsonl} (local date of the collision's end or the
 * incomplete), while Save collisions is on. Nothing here is ever sent anywhere.
 *
 * <p>Threads: {@link #record} from any thread (the client thread in practice); the encoding and
 * the file write run on the injected executor, never the caller's thread. The {@link #setListener
 * listener} is called on the caller's thread, whether or not Save collisions is on.
 */
@Singleton
public final class CollisionLog
{
    private final Gson gson;
    private final DailyJsonlAppender days;
    private final BooleanSupplier save;
    /** Told about each recorded collision and incomplete (the replay recorder); may be null. */
    private volatile Consumer<Object> listener;

    @Inject
    CollisionLog(Gson gson, ScheduledExecutorService executor, RflConfig config)
    {
        this(gson, executor, RuneLite.RUNELITE_DIR.toPath().resolve("rfl").resolve("collisions"),
            config::saveCollisions);
    }

    /** @param save whether lines are written to disk right now (the Save collisions setting) */
    public CollisionLog(Gson gson, ScheduledExecutorService executor, Path dir, BooleanSupplier save)
    {
        this.gson = gson;
        this.days = new DailyJsonlAppender(dir, executor);
        this.save = save;
    }

    /**
     * Called with each {@link Collision} or {@link Incomplete} passed to {@code record}, on the
     * caller's thread, whether or not Save collisions is on. Null clears it.
     */
    public void setListener(Consumer<Object> listener)
    {
        this.listener = listener;
    }

    public void record(Collision collision)
    {
        write(collision, collision.endMs);
    }

    public void record(Incomplete incomplete)
    {
        write(incomplete, incomplete.timeMs);
    }

    private void write(Object line, long epochMs)
    {
        Consumer<Object> heard = listener;
        if (heard != null)
        {
            heard.accept(line);
        }
        if (save.getAsBoolean())
        {
            days.append(() -> gson.toJson(line), epochMs);
        }
    }

    /** Any thread: epoch ms the last line reached disk, 0 before any; for the panel's "Saved" tick. */
    public long lastSavedAtMs()
    {
        return days.lastSavedAtMs();
    }
}
