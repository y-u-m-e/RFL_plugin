package com.rfl;

import java.io.IOException;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * POSTs a {@link RflReport} to the audit gateway off the client thread via the injected
 * {@link OkHttpClient}, and requeues the batch's events when the server didn't settle them.
 *
 * <p>Requeue rule (spec Review Focus item 3): a network failure, {@code 429}, or {@code 5xx}
 * means "try again with the next batch". A {@code 2xx} accept or a {@code 400} malformed
 * rejection both mean the batch is settled — a {@code 400} will never succeed, so it is
 * dropped rather than retried forever.
 */
@Singleton
public final class ReportSender
{
    /** Visible to {@code com.rfl.game} (Task 4) so GameClient shares the same gateway host. */
    public static final String BASE_URL = "https://dev-api.ironforged.gg";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final Gson gson;
    private final EventQueue queue;

    @Inject
    ReportSender(final OkHttpClient client, final Gson gson, final EventQueue queue)
    {
        this.client = client;
        this.gson = gson;
        this.queue = queue;
    }

    /**
     * Sends one report batch. {@code drained} is the exact event list already removed from the
     * queue for this batch; it is put back (via {@link EventQueue#requeue}) if the send fails or
     * the server asks for a retry.
     *
     * @param report  report body to POST
     * @param drained events drained from the queue for this batch
     */
    void send(final RflReport report, final List<RflEvent> drained)
    {
        final Request request = new Request.Builder()
            .url(BASE_URL + "/plugins/rfl/report")
            .post(RequestBody.create(JSON, gson.toJson(report)))
            .build();

        client.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(final Call call, final IOException e)
            {
                queue.requeue(drained);
            }

            @Override
            public void onResponse(final Call call, final Response response) throws IOException
            {
                try
                {
                    if (shouldRequeue(response.code()))
                    {
                        queue.requeue(drained);
                    }
                }
                finally
                {
                    response.close();
                }
            }
        });
    }

    /**
     * Decides whether a batch should be retried for a given HTTP status.
     *
     * @param httpStatus status code returned by the server
     * @return false only for 2xx (accepted) and 400 (malformed, never retryable); true otherwise
     */
    static boolean shouldRequeue(final int httpStatus)
    {
        if (httpStatus == 400)
        {
            return false;
        }
        return httpStatus < 200 || httpStatus >= 300;
    }
}
