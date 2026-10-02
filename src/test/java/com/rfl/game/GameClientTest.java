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
import com.rfl.RflPlugin;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * {@link GameClient} exercised against a fake OkHttp application interceptor — the same
 * no-request-leaves-the-process technique {@code ReportSenderTest} uses, rather than a real
 * server. Covers the lobby GET's {@code X-RFL-Install} header (so a host's poll keeps their
 * game alive), the join 403 -> "Wrong passphrase" mapping, and that every error reaches the
 * callback rather than hanging.
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
        final GameClient client = enabled(new GameClient(okHttp, new GsonBuilder().create()));
        client.setIdentitySupplier(() -> new GameClient.Identity("Rsn", "install-1", 301));

        final GameClient.Result<List<GameSummary>> result = await(client::list);

        assertFalse(result.isOk());
        assertEquals("Can't reach the RFL API", result.error());
    }

    @Test
    public void unknownPassphraseOnJoinByPassphraseSaysSo() throws InterruptedException
    {
        final GameClient client = clientReturning(404, "{\"error\":\"game not found\"}", request -> { });
        client.setIdentitySupplier(() -> new GameClient.Identity("Rsn", "install-1", 301));

        final GameClient.Result<String> result = await(callback -> client.joinByPassphrase("nope", callback));

        assertFalse(result.isOk());
        assertEquals("No game with that passphrase", result.error());
    }

    @Test
    public void runtimeExceptionReadingTheResponseStillReachesTheCallback() throws InterruptedException
    {
        final OkHttpClient okHttp = new OkHttpClient.Builder()
            .addInterceptor(chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("")
                .body(new ResponseBody()
                {
                    @Override
                    public MediaType contentType()
                    {
                        return null;
                    }

                    @Override
                    public long contentLength()
                    {
                        return -1;
                    }

                    @Override
                    public BufferedSource source()
                    {
                        throw new IllegalStateException("simulated body failure");
                    }

                    @Override
                    public void close()
                    {
                        // Nothing to release; the default close() would call source() again.
                    }
                })
                .build())
            .build();
        final GameClient client = enabled(new GameClient(okHttp, new GsonBuilder().create()));

        final GameClient.Result<List<GameSummary>> result = await(client::list);

        assertFalse(result.isOk());
        assertEquals("Can't reach the RFL API", result.error());
    }

    @Test
    public void sendsNothingWhileReportingIsOff() throws InterruptedException
    {
        final AtomicReference<Request> sent = new AtomicReference<>();
        final GameClient client = clientReturning(200, "[]", sent::set);
        client.setEnabled(() -> false);
        client.setIdentitySupplier(() -> new GameClient.Identity("Rsn", "install-1", 301));

        final GameClient.Result<List<GameSummary>> listed = await(client::list);
        final GameClient.Result<String> joined = await(callback -> client.join("g1", "pp", callback));

        assertEquals(GameClient.REPORTING_OFF, listed.error());
        assertEquals(GameClient.REPORTING_OFF, joined.error());
        assertEquals(null, sent.get());
    }

    @Test
    public void sendsNothingWhileObservingEvenWithReportingOn() throws InterruptedException
    {
        final AtomicReference<Request> sent = new AtomicReference<>();
        final GameClient client = clientReturning(200, "[]", sent::set);
        client.setEnabled(() -> RflPlugin.reportingAllowed(true, true));
        client.setIdentitySupplier(() -> new GameClient.Identity("Rsn", "install-1", 301));

        final GameClient.Result<List<GameSummary>> listed = await(client::list);
        final GameClient.Result<String> created = await(callback -> client.create("g", "pp", callback));

        assertEquals(GameClient.REPORTING_OFF, listed.error());
        assertEquals(GameClient.REPORTING_OFF, created.error());
        assertEquals(null, sent.get());
    }

    private static GameClient enabled(final GameClient client)
    {
        client.setEnabled(() -> true);
        return client;
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
        return enabled(new GameClient(okHttp, new GsonBuilder().create()));
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
