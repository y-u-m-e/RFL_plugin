package com.rfl.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.Test;

import com.google.gson.GsonBuilder;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * {@link GameClient} exercised against a fake OkHttp application interceptor — the same
 * no-request-leaves-the-process technique {@code ReportSenderTest} uses, rather than a real
 * server. Optional per task-4-brief.md, but covers the two things it calls out by name: the
 * lobby GET's {@code X-RFL-Install} header (so a host's poll keeps their game alive), and the
 * join 403 -> "Wrong passphrase" mapping (Review Focus item 5: errors always reach the
 * callback, never a hang).
 */
public class GameClientTest
{
    @Test
    public void lobbyGetSendsTheCallersInstallIdHeader() throws InterruptedException
    {
        final AtomicReference<String> seenHeader = new AtomicReference<>();
        final GameClient client = clientReturning(200, "{\"game\":{\"id\":\"g1\"},\"teams\":[],\"players\":[]}",
            request -> seenHeader.set(request.header("X-RFL-Install")));
        client.setIdentitySupplier(() -> new GameClient.Identity("Rsn", "install-1", 301));

        final GameClient.Result<GameDetail> result = await(callback -> client.get("g1", callback));

        assertTrue(result.isOk());
        assertEquals("install-1", seenHeader.get());
    }

    @Test
    public void wrongPassphraseOn403MapsToOneLineMessage() throws InterruptedException
    {
        final GameClient client = clientReturning(403, "{\"error\":\"wrong passphrase\"}", request -> { });
        client.setIdentitySupplier(() -> new GameClient.Identity("Rsn", "install-1", 301));

        final GameClient.Result<String> result = await(callback -> client.join("g1", "nope", callback));

        assertFalse(result.isOk());
        assertEquals("Wrong passphrase", result.error());
    }

    @Test
    public void networkFailureMapsToOneLineMessage() throws InterruptedException
    {
        final OkHttpClient okHttp = new OkHttpClient.Builder()
            .addInterceptor(chain ->
            {
                throw new IOException("simulated network failure");
            })
            .build();
        final GameClient client = new GameClient(okHttp, new GsonBuilder().create());
        client.setIdentitySupplier(() -> new GameClient.Identity("Rsn", "install-1", 301));

        final GameClient.Result<List<GameSummary>> result = await(client::list);

        assertFalse(result.isOk());
        assertEquals("Can't reach the RFL API", result.error());
    }

    private static GameClient clientReturning(final int code, final String body, final Consumer<Request> onRequest)
    {
        final OkHttpClient okHttp = new OkHttpClient.Builder()
            .addInterceptor(chain ->
            {
                onRequest.accept(chain.request());
                return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("")
                    .body(ResponseBody.create(MediaType.parse("application/json"), body))
                    .build();
            })
            .build();
        return new GameClient(okHttp, new GsonBuilder().create());
    }

    private static <T> GameClient.Result<T> await(final Consumer<Consumer<GameClient.Result<T>>> call)
        throws InterruptedException
    {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<GameClient.Result<T>> result = new AtomicReference<>();
        call.accept(r ->
        {
            result.set(r);
            latch.countDown();
        });
        assertTrue("callback never fired", latch.await(2, TimeUnit.SECONDS));
        return result.get();
    }
}
