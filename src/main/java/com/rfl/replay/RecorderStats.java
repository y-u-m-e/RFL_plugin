package com.rfl.replay;

import com.rfl.replay.pitch.LocSkip;
import com.rfl.replay.pitch.PitchCapture;
import sh.yumekui.toolkit.model.RenderableModels;

import java.util.Arrays;

/**
 * The recorder's cost counters for its one performance summary per replay, written to the client
 * log when the file closes: average and worst ClientTick, the steady frames (no new model and no
 * start-up work), model capture failures, and how house models were found. It is the plugin's only
 * stutter diagnostic, so it is always on; it costs a few additions per frame.
 *
 * <p>Client thread only.
 */
public final class RecorderStats
{
    private static final double NANOS_PER_MICRO = 1000.0;
    private static final long NANOS_PER_MICRO_LONG = 1000;

    private long frameNanos;
    private long frames;
    /** Frames that captured no new model and did no start-up work: their count, total and worst cost. */
    private long steadyFrames;
    private long steadyNanos;
    private long steadyWorstNanos;
    /** The model-key part of those frames (a key build and lookup per player). */
    private long steadyModelNanos;
    private long steadyModelWorstNanos;
    /** Model reads that threw. */
    private int captureFailures;
    /** House model reads per {@link RenderableModels.Path}: [path][0] found a model, [path][1] did not. */
    private final int[][] locReads = new int[RenderableModels.Path.values().length][2];
    /** This ClientTick's file open, pitch scan and house capture time. */
    private long tickOpenNanos;
    private long tickPitchNanos;
    private long tickCaptureNanos;
    /** The recording's worst ClientTick and what it spent its time on. */
    private long worstTickNanos;
    private long worstOpenNanos;
    private long worstPitchNanos;
    private long worstCaptureNanos;
    private long worstSampleNanos;
    private int worstTickCycle;

    /** A new file. */
    void reset()
    {
        frameNanos = 0;
        frames = 0;
        steadyFrames = 0;
        steadyNanos = 0;
        steadyWorstNanos = 0;
        steadyModelNanos = 0;
        steadyModelWorstNanos = 0;
        captureFailures = 0;
        for (int[] reads : locReads)
        {
            Arrays.fill(reads, 0);
        }
        worstTickNanos = 0;
        worstOpenNanos = 0;
        worstPitchNanos = 0;
        worstCaptureNanos = 0;
        worstSampleNanos = 0;
        worstTickCycle = 0;
    }

    /** A sampled ClientTick begins. */
    void startTick()
    {
        tickOpenNanos = 0;
        tickPitchNanos = 0;
        tickCaptureNanos = 0;
    }

    void addOpenNanos(long nanos)
    {
        tickOpenNanos += nanos;
    }

    public void addPitchNanos(long nanos)
    {
        tickPitchNanos += nanos;
    }

    void addCaptureNanos(long nanos)
    {
        tickCaptureNanos += nanos;
    }

    public void captureFailed()
    {
        captureFailures++;
    }

    public void locRead(RenderableModels.Path path, boolean found)
    {
        locReads[path.ordinal()][found ? 0 : 1]++;
    }

    /**
     * A sampled ClientTick ends.
     *
     * @param spentNanos the whole tick's recorder time
     * @param sampleNanos the part spent reading and sampling players and balls
     * @param newModels whether the sampler captured a new model this tick
     * @param modelNanos the sampler's model-key time this tick
     */
    void endTick(long spentNanos, long sampleNanos, int cycle, boolean newModels, long modelNanos)
    {
        frameNanos += spentNanos;
        frames++;
        if (spentNanos > worstTickNanos)
        {
            worstTickNanos = spentNanos;
            worstOpenNanos = tickOpenNanos;
            worstPitchNanos = tickPitchNanos;
            worstCaptureNanos = tickCaptureNanos;
            worstSampleNanos = sampleNanos;
            worstTickCycle = cycle;
        }
        boolean startUpWork = tickOpenNanos != 0 || tickPitchNanos != 0 || tickCaptureNanos != 0;
        if (!newModels && !startUpWork)
        {
            steadyFrames++;
            steadyNanos += spentNanos;
            steadyWorstNanos = Math.max(steadyWorstNanos, spentNanos);
            steadyModelNanos += modelNanos;
            steadyModelWorstNanos = Math.max(steadyModelWorstNanos, modelNanos);
        }
    }

    long averageTickMicros()
    {
        return frames == 0 ? 0 : frameNanos / frames / NANOS_PER_MICRO_LONG;
    }

    int captureFailures()
    {
        return captureFailures;
    }

    /** {@code steadyFrames=.. steadyAvgUs=.. steadyWorstUs=.. modelKeyAvgUs=.. modelKeyWorstUs=..} */
    String steady()
    {
        return String.format("steadyFrames=%d steadyAvgUs=%.1f steadyWorstUs=%.1f"
                + " modelKeyAvgUs=%.2f modelKeyWorstUs=%.1f",
            steadyFrames, steadyFrames == 0 ? 0.0 : steadyNanos / (double) steadyFrames / NANOS_PER_MICRO,
            steadyWorstNanos / NANOS_PER_MICRO,
            steadyFrames == 0 ? 0.0 : steadyModelNanos / (double) steadyFrames / NANOS_PER_MICRO,
            steadyModelWorstNanos / NANOS_PER_MICRO);
    }

    /**
     * {@code locsCaptured=.. locsSkipped=.. locSkips=.. locVia(ok/none)=.. pitchTicksMax=..
     * pitchesAbandoned=.. locWorstReadUs=.. locWorstStepUs=..}
     */
    String locs(PitchCapture pitch)
    {
        int[] skips = pitch.locSkips();
        int skipped = 0;
        StringBuilder reasons = new StringBuilder();
        for (LocSkip why : LocSkip.values())
        {
            skipped += skips[why.ordinal()];
            reasons.append(reasons.length() == 0 ? "" : ",").append(why.label).append(':')
                .append(skips[why.ordinal()]);
        }
        StringBuilder paths = new StringBuilder();
        for (RenderableModels.Path path : RenderableModels.Path.values())
        {
            int[] reads = locReads[path.ordinal()];
            paths.append(paths.length() == 0 ? "" : ",").append(path.label).append(':').append(reads[0]).append('/')
                .append(reads[1]);
        }
        return "locsCaptured=" + pitch.locsCaptured() + " locsSkipped=" + skipped + " locSkips=" + reasons
            + " locVia(ok/none)=" + paths + " pitchTicksMax=" + pitch.ticksMax() + " pitchesAbandoned="
            + pitch.abandoned() + String.format(" locWorstReadUs=%.1f locWorstStepUs=%.1f",
            pitch.worstReadNanos() / NANOS_PER_MICRO, pitch.worstStepNanos() / NANOS_PER_MICRO);
    }

    /** The worst ClientTick of the recording, split into open, pitch, house capture and sampling. */
    String worst()
    {
        return String.format("worstTickUs=%.1f worstTickCyc=%d worstTickOpenUs=%.1f worstTickPitchUs=%.1f"
                + " worstTickCaptureUs=%.1f worstTickSampleUs=%.1f",
            worstTickNanos / NANOS_PER_MICRO, worstTickCycle, worstOpenNanos / NANOS_PER_MICRO,
            worstPitchNanos / NANOS_PER_MICRO, worstCaptureNanos / NANOS_PER_MICRO,
            worstSampleNanos / NANOS_PER_MICRO);
    }
}
