package com.rfl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The pitch line's house model capture, spread over ClientTicks (spec §2.3). {@link #begin} takes
 * a built pitch line and its locs; each {@link #step} reads locs for up to the budget, then hands
 * the new model lines to the {@link Sink} before any line that refers to them.
 *
 * <p>The file's first pitch streams: it goes out on its first step with the rows read so far (may
 * be none), and each later step's new rows follow as one {@code locs} line. Any later pitch (after
 * a reload) goes out once every loc is read, with every row, and never gets {@code locs} lines.
 * A {@link #begin} while a pitch is pending gives up on it: a streaming one keeps what it wrote,
 * any other is dropped unwritten. {@link #stop} writes a pending non-streaming pitch with the rows
 * read so far, so a file always ends with its pitch out.
 *
 * <p>Client thread only. Pure apart from the sink: no client access.
 */
final class PitchCapture
{
    /** Where the lines go, in call order. */
    interface Sink
    {
        /** {@code model} lines; called before any pitch or locs line that refers to them. */
        void models(List<Map<String, Object>> lines);

        /** The pitch line, complete and never touched again. */
        void pitch(Map<String, Object> line);

        /** A {@code locs} line, complete and never touched again. */
        void locs(Map<String, Object> line);
    }

    private final Sink sink;
    private final long budgetNanos;
    private final LongSupplier clock;

    private ReplaySampler sampler;
    /** The pitch line not yet written (null when none or already out). */
    private Map<String, Object> line;
    /** The pass the pending pitch waits on (null when none). */
    private ReplaySampler.LocPass pass;
    private boolean streams;
    private int rowsWritten;
    private int ticks;
    /** Whether the file has a pitch line yet. */
    private boolean written;

    /** Debug totals for the file. */
    private int locsCaptured;
    private final int[] locSkips = new int[ReplaySampler.LocSkip.values().length];
    private int ticksMax;
    private int abandoned;
    private long worstReadNanos;
    private long worstStepNanos;

    PitchCapture(Sink sink, long budgetNanos, LongSupplier clock)
    {
        this.sink = sink;
        this.budgetNanos = budgetNanos;
        this.clock = clock;
    }

    /** {@code {"t":"locs","locs":[[modelId, localX, localY, groundHeight], ...]}}: more rows for the first pitch. */
    static Map<String, Object> locsLine(List<int[]> rows)
    {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("t", "locs");
        out.put("locs", rows);
        return out;
    }

    /** A new file: forgets any pending pitch and the debug totals. */
    void reset()
    {
        sampler = null;
        line = null;
        pass = null;
        written = false;
        locsCaptured = 0;
        java.util.Arrays.fill(locSkips, 0);
        ticksMax = 0;
        abandoned = 0;
        worstReadNanos = 0;
        worstStepNanos = 0;
    }

    /**
     * Starts capturing {@code locs} for {@code pitchLine}, whose {@code locs} key is filled in when
     * it is written. Gives up on a pitch still pending.
     */
    void begin(ReplaySampler sampler, Map<String, Object> pitchLine, List<ReplaySampler.Loc> locs)
    {
        abandon();
        this.sampler = sampler;
        line = pitchLine;
        pass = sampler.locPass(locs);
        ticks = 0;
        rowsWritten = 0;
        // Only the file's first pitch streams: the viewer merges every locs line into it.
        streams = !written;
    }

    /**
     * Gives up on a pending pitch (a reload: its scene is gone). A streaming one keeps what it
     * wrote; any other is dropped unwritten. No-op when nothing is pending.
     */
    void abandon()
    {
        if (pass == null)
        {
            return;
        }
        abandoned++;
        if (streams)
        {
            pass.abandon();
            finish();
        }
        pass = null;
        line = null;
    }

    /** Whether a pitch is waiting on house models. */
    boolean pending()
    {
        return pass != null;
    }

    /** One ClientTick: reads locs within the budget and writes what is ready. No-op when nothing is pending. */
    void step()
    {
        if (pass == null)
        {
            return;
        }
        ticks++;
        final long start = clock.getAsLong();
        final boolean done = pass.step(budgetNanos, clock);
        worstStepNanos = Math.max(worstStepNanos, clock.getAsLong() - start);
        sink.models(sampler.newModelLines());
        if (streams)
        {
            writeRows();
        }
        if (done)
        {
            finish();
        }
    }

    /** The file is closing: finishes a pending pitch with the rows read so far. */
    void stop()
    {
        if (pass != null)
        {
            pass.abandon();
            finish();
        }
    }

    private void writeRows()
    {
        final List<int[]> rows = pass.rows();
        // Copied: the line is serialised later, on the writer's thread.
        final List<int[]> fresh = new ArrayList<>(rows.subList(rowsWritten, rows.size()));
        rowsWritten = rows.size();
        if (line != null)
        {
            line.put("locs", fresh);
            sink.pitch(line);
            line = null;
            written = true;
        }
        else if (!fresh.isEmpty())
        {
            sink.locs(locsLine(fresh));
        }
    }

    private void finish()
    {
        sink.models(sampler.newModelLines());
        if (streams)
        {
            writeRows();
        }
        else
        {
            line.put("locs", new ArrayList<>(pass.rows()));
            sink.pitch(line);
            written = true;
        }
        locsCaptured += pass.rows().size();
        final int[] skips = pass.skips();
        for (int k = 0; k < skips.length; k++)
        {
            locSkips[k] += skips[k];
        }
        ticksMax = Math.max(ticksMax, ticks);
        worstReadNanos = Math.max(worstReadNanos, pass.worstReadNanos());
        line = null;
        pass = null;
    }

    int locsCaptured()
    {
        return locsCaptured;
    }

    /** Skips for the file, indexed by {@link ReplaySampler.LocSkip#ordinal()}. */
    int[] locSkips()
    {
        return locSkips.clone();
    }

    int ticksMax()
    {
        return ticksMax;
    }

    int abandoned()
    {
        return abandoned;
    }

    /** Debug: the slowest single loc read of the file (one model's capture). */
    long worstReadNanos()
    {
        return worstReadNanos;
    }

    /** Debug: the slowest {@link #step}'s loc reading (over the budget only by its last read). */
    long worstStepNanos()
    {
        return worstStepNanos;
    }
}
