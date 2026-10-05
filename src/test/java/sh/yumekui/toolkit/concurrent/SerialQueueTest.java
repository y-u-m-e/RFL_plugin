package sh.yumekui.toolkit.concurrent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * {@link SerialQueue}: steps run in the order queued even on a multi-threaded executor, and a step
 * that throws is reported without stopping the steps behind it.
 */
public class SerialQueueTest
{
    /** Enough steps, from enough threads' worth of executor, to show any reordering. */
    private static final int STEPS = 2000;
    private static final int THREADS = 8;
    private static final long WAIT_SECONDS = 10;

    @Test
    public void stepsRunInQueueOrderOnAMultiThreadedExecutor() throws Exception
    {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        List<Integer> ran = Collections.synchronizedList(new ArrayList<>());
        SerialQueue queue = new SerialQueue(executor, error -> { });
        for (int step = 0; step < STEPS; step++)
        {
            int number = step;
            queue.enqueue(() -> ran.add(number));
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));

        List<Integer> expected = new ArrayList<>();
        for (int step = 0; step < STEPS; step++)
        {
            expected.add(step);
        }
        assertEquals(expected, ran);
    }

    @Test
    public void aThrowingStepIsReportedAndTheNextStepStillRuns()
    {
        List<String> events = new ArrayList<>();
        // Runs each drain on the caller's thread, so the test can check at once.
        SerialQueue queue = new SerialQueue(Runnable::run, error -> events.add("failed: " + error.getMessage()));
        queue.enqueue(() -> events.add("first"));
        queue.enqueue(() ->
        {
            throw new IllegalStateException("boom");
        });
        queue.enqueue(() -> events.add("after"));

        assertEquals(List.of("first", "failed: boom", "after"), events);
    }
}
