package sh.yumekui.toolkit.time;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/**
 * {@link FrameBudget} with a fake clock where every step costs a fixed time: it stops once the
 * budget is spent, always runs at least one step, and reports the slowest step.
 */
public class FrameBudgetTest
{
    private static final long STEP_NANOS = 300;
    private static final long BUDGET_NANOS = 1000;

    @Test
    public void stopsOnceTheBudgetIsSpentAndReportsTheSlowestStep()
    {
        AtomicLong clock = new AtomicLong();
        AtomicInteger done = new AtomicInteger();
        long slowest = FrameBudget.run(BUDGET_NANOS, clock::get, () -> done.get() < 10, () ->
        {
            done.incrementAndGet();
            clock.addAndGet(STEP_NANOS);
        });
        // 300, 600, 900 are under 1000; the fourth step reaches 1200 and ends the call.
        assertEquals(4, done.get());
        assertEquals(STEP_NANOS, slowest);
    }

    @Test
    public void runsAtLeastOneStepEvenWithNoBudgetAndNoneWhenTheJobIsDone()
    {
        AtomicLong clock = new AtomicLong();
        AtomicInteger done = new AtomicInteger();
        FrameBudget.run(0, clock::get, () -> true, () ->
        {
            done.incrementAndGet();
            clock.addAndGet(STEP_NANOS);
        });
        assertEquals(1, done.get());

        assertEquals("nothing left to do", 0, FrameBudget.run(BUDGET_NANOS, clock::get, () -> false, done::incrementAndGet));
        assertEquals(1, done.get());
    }
}
