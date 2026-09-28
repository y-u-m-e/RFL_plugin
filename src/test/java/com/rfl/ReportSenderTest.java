package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.Test;

import com.google.gson.GsonBuilder;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * {@link ReportSender#send} is exercised end-to-end here with a real {@link EventQueue} and a
 * real {@link OkHttpClient} whose application interceptor stands in for the network — it never
 * calls {@code chain.proceed()}, so no request actually leaves the process. This covers Review
 * Focus item 3: a network failure or a retryable status must requeue the drained events; a
 * settled status (2xx or 400) must not.
 */
public class ReportSenderTest
{
    @Test
    public void requeueRules()
    {
        assertFalse(ReportSender.shouldRequeue(200));
        assertFalse(ReportSender.shouldRequeue(400));
        assertTrue(ReportSender.shouldRequeue(429));
        assertTrue(ReportSender.shouldRequeue(503));
    }

    @Test
    public void networkFailureRequeues() throws InterruptedException
    {
        assertRequeueOutcome(clientThrowing(new IOException("simulated network failure")), true);
    }

    @Test
    public void rateLimitedRequeues() throws InterruptedException
    {
        assertRequeueOutcome(clientReturning(429), true);
    }

    @Test
    public void serverErrorRequeues() throws InterruptedException
    {
        assertRequeueOutcome(clientReturning(503), true);
    }

    @Test
    public void acceptedDoesNotRequeue() throws InterruptedException
    {
        assertRequeueOutcome(clientReturning(200), false);
    }

    @Test
    public void malformedDoesNotRequeue() throws InterruptedException
    {
        assertRequeueOutcome(clientReturning(400), false);
    }

    private static void assertRequeueOutcome(final OkHttpClient client, final boolean expectRequeued)
        throws InterruptedException
    {
        final EventQueue queue = new EventQueue();
        final ReportSender sender = new ReportSender(client, new GsonBuilder().create(), queue);

        final RflEvent event = RflEvent.pluginToggle(0, 0, "X", true);
        final RflReport report = new RflReport("Rsn", "install-1", 1, 0L, false,
            List.of(), List.of(), List.of(event));

        sender.send(report, List.of(event));

        final List<RflEvent> requeued = drainWithWait(queue, 500);
        assertEquals(expectRequeued, !requeued.isEmpty());
    }

    /**
     * Polls a real queue briefly for the async callback (network failure or response) to land,
     * rather than adding a test-only hook to {@link ReportSender} or {@link EventQueue}.
     */
    private static List<RflEvent> drainWithWait(final EventQueue queue, final long timeoutMs)
        throws InterruptedException
    {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            final List<RflEvent> drained = queue.drain();
            if (!drained.isEmpty())
            {
                return drained;
            }
            Thread.sleep(20);
        }
        return List.of();
    }

    private static OkHttpClient clientThrowing(final IOException e)
    {
        return new OkHttpClient.Builder()
            .addInterceptor(chain ->
            {
                throw e;
            })
            .build();
    }

    private static OkHttpClient clientReturning(final int code)
    {
        return new OkHttpClient.Builder()
            .addInterceptor(chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .body(ResponseBody.create(MediaType.parse("application/json"), "{}"))
                .build())
            .build();
    }
}
