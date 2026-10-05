package com.rfl.replay.pitch;

import com.rfl.replay.ReplaySampler;
import com.rfl.replay.ReplayWarmUp;
import sh.yumekui.toolkit.model.ModelCapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * {@link PitchCapture}: line order for spread-out house capture (spec §2.3), and what a reload or
 * a stop mid-capture writes.
 */
public class PitchCaptureTest
{
    private static final long BUDGET = 1_000_000L;
    /** Each capture costs 400 us, so a 1 ms step reads three locs. */
    private static final long COST = 400_000L;

    private final AtomicLong clock = new AtomicLong();
    private final List<Map<String, Object>> out = new ArrayList<>();
    private final PitchCapture capture = new PitchCapture(new PitchCapture.Sink()
    {
        @Override
        public void models(List<Map<String, Object>> lines)
        {
            out.addAll(lines);
        }

        @Override
        public void pitch(Map<String, Object> line)
        {
            out.add(line);
        }

        @Override
        public void locs(Map<String, Object> line)
        {
            out.add(line);
        }
    }, BUDGET, clock::get);

    /** {@code n} locs with distinct ids from {@code firstId}, each a new key costing {@link #COST}. */
    private List<Loc> locs(int firstId, int n)
    {
        List<Loc> list = new ArrayList<>();
        for (int k = 0; k < n; k++)
        {
            final int shift = firstId + k;
            list.add(Loc.withReasons(firstId + k, 10, 0, 0, 64 + k * 128, 64, 0, () ->
            {
                clock.addAndGet(COST);
                int[] v = { shift, 0, 0, 0, 1, 0, 0, 0, 1 };
                return LocModel.of(new ModelCapture.Geometry(v, new int[] { 0, 1, 2 }, new int[] { 1, 2, 3 }));
            }));
        }
        return list;
    }

    private static Map<String, Object> pitchLine()
    {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", "pitch");
        line.put("locs", null);
        line.put("paint", new int[0]);
        return line;
    }

    private List<String> types()
    {
        List<String> t = new ArrayList<>();
        for (Map<String, Object> line : out)
        {
            t.add((String) line.get("t"));
        }
        return t;
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(Map<String, Object> line)
    {
        return (List<Object[]>) line.get("locs");
    }

    /** Every pitch/locs row refers to a model line written earlier, and no model id is written twice. */
    private void assertModelsPrecedeUse()
    {
        Set<Integer> seen = new HashSet<>();
        for (Map<String, Object> line : out)
        {
            Object t = line.get("t");
            if ("model".equals(t))
            {
                assertTrue("model id written once", seen.add((Integer) line.get("id")));
            }
            else
            {
                assertNotNull(t + " has its rows filled in", rows(line));
                for (Object[] row : rows(line))
                {
                    assertTrue(t + " row refers to model " + row[0] + " before its line", seen.contains(row[0]));
                }
            }
        }
    }

    private int count(String type)
    {
        int n = 0;
        for (String t : types())
        {
            n += type.equals(t) ? 1 : 0;
        }
        return n;
    }

    @Test
    public void firstPitchGoesOutOnTheFirstStepWithWhatIsCapturedSoFar()
    {
        ReplaySampler sampler = new ReplaySampler();
        Map<String, Object> line = pitchLine();
        capture.begin(sampler, line, locs(100, 5));
        assertTrue(out.isEmpty());

        capture.step();
        assertEquals(List.of("model", "model", "model", "pitch"), types());
        assertSame(line, out.get(3));
        assertEquals(3, rows(line).size());
        assertTrue(capture.pending());

        capture.step();
        assertEquals(List.of("model", "model", "model", "pitch", "model", "model", "locs"), types());
        assertEquals(2, rows(out.get(6)).size());
        assertFalse(capture.pending());
        assertEquals(5, capture.locsCaptured());
        assertEquals(2, capture.ticksMax());
        assertModelsPrecedeUse();
    }

    @Test
    public void everyLocsLineFollowsTheModelLinesItReferences()
    {
        ReplaySampler sampler = new ReplaySampler();
        List<Loc> list = locs(200, 10);
        // Repeats of earlier keys: rows with no new model line of their own.
        list.addAll(locs(200, 4));
        capture.begin(sampler, pitchLine(), list);
        while (capture.pending())
        {
            capture.step();
        }
        assertEquals(1, count("pitch"));
        assertEquals(10, count("model"));
        assertTrue(count("locs") >= 2);
        assertEquals(14, capture.locsCaptured());
        assertModelsPrecedeUse();
    }

    @Test
    public void emptyHouseWritesAPitchWithNoLocs()
    {
        capture.begin(new ReplaySampler(), pitchLine(), List.of());
        capture.step();
        assertEquals(List.of("pitch"), types());
        assertTrue(rows(out.get(0)).isEmpty());
        assertFalse(capture.pending());
    }

    @Test
    public void reloadMidCaptureWritesTheNewPitchWholeWithNoLocsLines()
    {
        ReplaySampler sampler = new ReplaySampler();
        capture.begin(sampler, pitchLine(), locs(300, 6));
        capture.step();
        int afterFirst = out.size();

        Map<String, Object> second = pitchLine();
        capture.begin(sampler, second, locs(400, 7));
        assertEquals("giving up on the first pitch writes nothing new", afterFirst, out.size());
        assertEquals(1, capture.abandoned());
        assertEquals(3, capture.locSkips()[LocSkip.UNFINISHED.ordinal()]);

        capture.step();
        assertEquals("the reload pitch waits for its models", afterFirst + 3, out.size());
        while (capture.pending())
        {
            capture.step();
        }
        List<String> tail = types().subList(afterFirst, out.size());
        assertFalse(tail.contains("locs"));
        assertEquals("pitch", tail.get(tail.size() - 1));
        assertSame(second, out.get(out.size() - 1));
        assertEquals(7, rows(second).size());
        assertModelsPrecedeUse();
    }

    @Test
    public void reloadDuringAReloadPitchDropsItUnwritten()
    {
        ReplaySampler sampler = new ReplaySampler();
        capture.begin(sampler, pitchLine(), locs(500, 3));
        capture.step();
        Map<String, Object> dropped = pitchLine();
        capture.begin(sampler, dropped, locs(600, 6));
        capture.step();
        capture.begin(sampler, pitchLine(), locs(700, 2));
        while (capture.pending())
        {
            capture.step();
        }
        assertEquals(2, count("pitch"));
        assertTrue(out.stream().noneMatch(l -> l == dropped));
        assertEquals(1, capture.abandoned());
        assertModelsPrecedeUse();
    }

    @Test
    public void stopMidStreamingCaptureLeavesNothingHalfWritten()
    {
        ReplaySampler sampler = new ReplaySampler();
        capture.begin(sampler, pitchLine(), locs(800, 8));
        capture.step();
        int before = out.size();

        capture.stop();
        assertEquals("no locs line with nothing new", before, out.size());
        assertFalse(capture.pending());
        assertEquals(5, capture.locSkips()[LocSkip.UNFINISHED.ordinal()]);
        capture.step();
        assertEquals("nothing after stop", before, out.size());
        assertModelsPrecedeUse();
    }

    @Test
    public void stopMidReloadCaptureWritesThatPitchWithTheRowsReadSoFar()
    {
        ReplaySampler sampler = new ReplaySampler();
        capture.begin(sampler, pitchLine(), locs(900, 2));
        capture.step();
        Map<String, Object> second = pitchLine();
        capture.begin(sampler, second, locs(1000, 8));
        capture.step();

        capture.stop();
        assertSame(second, out.get(out.size() - 1));
        assertEquals(3, rows(second).size());
        assertFalse(capture.pending());
        assertEquals(2, count("pitch"));
        assertEquals(0, count("locs"));
        assertModelsPrecedeUse();
    }

    @Test
    public void resetStartsANewFileWhoseFirstPitchStreams()
    {
        ReplaySampler sampler = new ReplaySampler();
        capture.begin(sampler, pitchLine(), locs(1100, 1));
        capture.step();
        capture.reset();
        assertEquals(0, capture.locsCaptured());

        out.clear();
        capture.begin(new ReplaySampler(), pitchLine(), locs(1200, 5));
        capture.step();
        assertEquals("pitch", types().get(types().size() - 1));
    }

    @Test
    public void abandonGivesUpOnAPendingPitchAtOnce()
    {
        ReplaySampler sampler = new ReplaySampler();
        capture.begin(sampler, pitchLine(), locs(1300, 6));
        capture.step();
        int before = out.size();
        capture.abandon();
        assertFalse(capture.pending());
        assertEquals(before, out.size());
        assertEquals(1, capture.abandoned());
        capture.abandon();
        assertEquals("no-op when nothing is pending", 1, capture.abandoned());
        assertEquals(COST, capture.worstReadNanos());
        assertTrue(capture.worstStepNanos() >= BUDGET);
    }

    @Test
    public void warmUpRunsWithoutAClient()
    {
        assertTrue(ReplayWarmUp.run(new com.google.gson.GsonBuilder().create()));
    }
}
