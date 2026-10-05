package sh.yumekui.toolkit.time;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Spreads a long job over frames: each call runs steps until the job is done or a time budget is
 * spent. The budget is checked after each step, so at least one step always runs and one slow step
 * can overrun it; a job therefore always makes progress.
 */
public final class FrameBudget
{
    private FrameBudget()
    {
    }

    /**
     * Runs {@code step} while {@code more} is true, stopping once {@code budgetNanos} have passed on
     * {@code clock} since the call began.
     *
     * @return the slowest single step this call, in the clock's units
     */
    public static long run(long budgetNanos, LongSupplier clock, BooleanSupplier more, Runnable step)
    {
        long start = clock.getAsLong();
        long before = start;
        long slowest = 0;
        while (more.getAsBoolean())
        {
            step.run();
            long now = clock.getAsLong();
            slowest = Math.max(slowest, now - before);
            before = now;
            if (now - start >= budgetNanos)
            {
                break;
            }
        }
        return slowest;
    }
}
