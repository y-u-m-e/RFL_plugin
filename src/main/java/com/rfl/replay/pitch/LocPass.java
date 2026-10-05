package com.rfl.replay.pitch;

import sh.yumekui.toolkit.time.FrameBudget;
import com.rfl.replay.ReplaySampler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Pitch-time house object capture spread over several ClientTicks: each {@link #step} reads locs
 * until its time budget is spent (at least one per step), registering new keys' models with the
 * sampler as it goes. Once {@link #done}, {@link #rows} is the {@code pitch.locs} list.
 *
 * <p>Client thread only.
 */
public final class LocPass
{
    private final ReplaySampler sampler;
    private final List<Loc> locs;
    private final LocNames names;
    private int next;
    private final List<Object[]> rows = new ArrayList<>();
    /** Names of the loc ids this pass introduced to the file, not yet handed out ({@link #takeNames}). */
    private final Map<String, String> newNames = new LinkedHashMap<>();
    private final int[] skips = new int[LocSkip.values().length];
    /** Keys whose capture already failed this pass, so the same model is not tried again. */
    private final Set<String> failedKeys = new HashSet<>();
    private long worstReadNanos;

    /**
     * @param sampler where new models are registered, so they share the file's model ids
     * @param names the file's loc names, so each name goes out once per file
     */
    public LocPass(ReplaySampler sampler, List<Loc> locs, LocNames names)
    {
        this.sampler = sampler;
        this.locs = locs;
        this.names = names;
    }

    /**
     * Reads locs until all are done or {@code budgetNanos} has passed on {@code clock} (checked after
     * each loc, so one slow model can overrun it). Returns {@link #done}.
     */
    public boolean step(long budgetNanos, LongSupplier clock)
    {
        long slowest = FrameBudget.run(budgetNanos, clock, () -> next < locs.size(), () -> read(locs.get(next++)));
        worstReadNanos = Math.max(worstReadNanos, slowest);
        return done();
    }

    private void read(Loc loc)
    {
        if (loc.capture == null)
        {
            skips[LocSkip.NO_RENDERABLE.ordinal()]++;
            return;
        }
        String key = loc.key();
        if (failedKeys.contains(key))
        {
            skips[LocSkip.SAME_KEY_FAILED.ordinal()]++;
            return;
        }
        // Holds the capture's result, so a failure can say why; set only when the key is new.
        LocModel[] captured = new LocModel[1];
        int id = sampler.locModelId(key, () ->
        {
            captured[0] = loc.capture.get();
            return captured[0] == null ? null : captured[0].geometry;
        });
        if (id < 0)
        {
            failedKeys.add(key);
            LocSkip why = captured[0] == null || captured[0].skip == null ? LocSkip.NO_MODEL : captured[0].skip;
            skips[why.ordinal()]++;
            return;
        }
        rows.add(loc.row(id));
        // Looked up on the client thread inside this step's budget; the recorder caches each id.
        String name = names.nameIfNew(loc.id);
        if (name != null)
        {
            newNames.put(String.valueOf(loc.id), name);
        }
    }

    /**
     * {@code {"<locId>": "<object name>"}} for the loc ids first given a row since the last call, for
     * the {@code names} of the line those rows go out in.
     */
    public Map<String, String> takeNames()
    {
        Map<String, String> out = new LinkedHashMap<>(newNames);
        newNames.clear();
        return out;
    }

    /** The slowest single loc read so far (a new key's capture, or a lookup). */
    public long worstReadNanos()
    {
        return worstReadNanos;
    }

    /** Gives up on the locs not read yet, counting them {@link LocSkip#UNFINISHED}. */
    public void abandon()
    {
        skips[LocSkip.UNFINISHED.ordinal()] += locs.size() - next;
        next = locs.size();
    }

    public boolean done()
    {
        return next >= locs.size();
    }

    /** Locs read so far. */
    public int read()
    {
        return next;
    }

    /** {@code pitch.locs} rows {@code [modelId, localX, localY, groundHeight, locId, kind]} so far. */
    public List<Object[]> rows()
    {
        return rows;
    }

    /** Skips so far, indexed by {@link LocSkip#ordinal()}. */
    public int[] skips()
    {
        return skips.clone();
    }

    /** Every skip so far, whatever the reason. */
    public int skipped()
    {
        int total = 0;
        for (int count : skips)
        {
            total += count;
        }
        return total;
    }
}
