package sh.yumekui.toolkit.concurrent;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs queued steps one at a time, in the order they were queued, on any executor. Only one drain
 * task is ever submitted at a time, so the order holds even on a multi-threaded executor, and a
 * burst of steps costs one executor task rather than one each.
 *
 * <p>A step that throws is handed to the failure handler and the drain carries on with the next
 * step: letting it escape would leave the "draining" flag set forever and strand every later step.
 *
 * <p>Threads: {@link #enqueue} from any thread.
 */
public final class SerialQueue
{
    private final Queue<Runnable> steps = new ConcurrentLinkedQueue<>();
    /** True while a drain task is submitted or running; guards against two at once. */
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final Executor executor;
    private final Consumer<RuntimeException> onFailure;

    /**
     * @param executor where the drain task runs
     * @param onFailure told about a step that threw, on the drain thread
     */
    public SerialQueue(Executor executor, Consumer<RuntimeException> onFailure)
    {
        this.executor = executor;
        this.onFailure = onFailure;
    }

    /** Queues one step and, when nothing is draining, submits the drain task. */
    public void enqueue(Runnable step)
    {
        steps.add(step);
        if (draining.compareAndSet(false, true))
        {
            executor.execute(this::drain);
        }
    }

    /**
     * Runs queued steps until the queue is empty, then clears the flag and looks once more: a step
     * can land between the last poll and the flag clearing, and its producer saw the flag still
     * set, so nobody else would schedule a drain for it (a lost wake-up). This task reclaims the
     * flag and carries on instead.
     */
    private void drain()
    {
        while (true)
        {
            Runnable step;
            while ((step = steps.poll()) != null)
            {
                try
                {
                    step.run();
                }
                catch (RuntimeException e)
                {
                    onFailure.accept(e);
                }
            }
            draining.set(false);
            if (steps.isEmpty() || !draining.compareAndSet(false, true))
            {
                return;
            }
        }
    }
}
