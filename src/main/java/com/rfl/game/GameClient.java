package com.rfl.game;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.rfl.ReportSender;

/**
 * Talks to {@code /plugins/rfl/games*} on the gateway ({@link ReportSender#BASE_URL}) off the
 * client thread via the injected {@link OkHttpClient}. Every call is async: it returns
 * immediately and delivers a {@link Result} to the given callback from an OkHttp callback
 * thread — never the client thread, and never by blocking the caller.
 *
 * <p>RSN, install ID and world are never read from live client state here; they come from the
 * {@link Identity} supplied by {@link #setIdentitySupplier}, which {@code RflPlugin} points at
 * its own config/client reads. A call made with no identity set yet fails fast with a
 * {@link Result#error} rather than sending a request with blank identity fields.
 */
@Singleton
public final class GameClient
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String GAMES_PATH = "/plugins/rfl/games";
    private static final String INSTALL_HEADER = "X-RFL-Install";

    /** Shown when a call is made before {@link #setIdentitySupplier} has an identity to give. */
    private static final String NOT_READY = "Not ready yet";
    /** Review Focus item 5: the API being unreachable gets this one line, not a stack trace. */
    private static final String NETWORK_ERROR = "Can't reach the RFL API";
    private static final String GAME_NOT_FOUND = "game not found";
    private static final String NO_SUCH_PASSPHRASE = "No game with that passphrase";

    private final OkHttpClient client;
    private final Gson gson;

    private volatile Supplier<Identity> identitySupplier = () -> null;

    @Inject
    GameClient(final OkHttpClient client, final Gson gson)
    {
        this.client = client;
        this.gson = gson;
    }

    /**
     * Sets where RSN/install id/world come from. {@code RflPlugin} (Task 5/6) should read the
     * current identity itself (on the client thread, where that live state is safe to read) and
     * supply it here; this class never reads {@code Client} or {@code ClientThread} directly.
     *
     * @param identitySupplier supplies the current identity, or returns null when not yet known
     */
    public void setIdentitySupplier(final Supplier<Identity> identitySupplier)
    {
        this.identitySupplier = identitySupplier;
    }

    /**
     * {@code GET /plugins/rfl/games} — active games, newest first. Needs no identity.
     *
     * @param callback receives the list, or a one-line error
     */
    public void list(final Consumer<Result<List<GameSummary>>> callback)
    {
        final Request request = new Request.Builder().url(ReportSender.BASE_URL + GAMES_PATH).build();
        enqueue(request, callback, body ->
        {
            final ListBody parsed = gson.fromJson(body, ListBody.class);
            return parsed == null || parsed.games == null ? List.of() : parsed.games;
        });
    }

    /**
     * {@code POST /plugins/rfl/games} — hosts a new game; the host auto-joins.
     *
     * @param name       game name (≤ 32 chars; the server trims/validates again)
     * @param passphrase passphrase (≤ 64 chars)
     * @param callback   receives the new game's id, or a one-line error (e.g. 409 "passphrase
     *                   already in use")
     */
    public void create(final String name, final String passphrase, final Consumer<Result<String>> callback)
    {
        withIdentity(callback, identity ->
        {
            final CreateBody body = new CreateBody(identity.rsn, identity.installId, name, passphrase, identity.world);
            enqueue(postRequest(GAMES_PATH, body), callback, this::readId);
        });
    }

    /**
     * {@code GET /plugins/rfl/games/:id}. Sends {@code X-RFL-Install} (not a query parameter —
     * the server deliberately keeps it out of request logs) so a host's own lobby poll keeps
     * their game alive; a non-host's poll sends it too, harmlessly.
     *
     * @param id       game id
     * @param callback receives the game detail, or a one-line error
     */
    public void get(final String id, final Consumer<Result<GameDetail>> callback)
    {
        final Identity identity = identitySupplier.get();
        final Request.Builder builder = new Request.Builder().url(ReportSender.BASE_URL + GAMES_PATH + "/" + id);
        if (identity != null)
        {
            builder.header(INSTALL_HEADER, identity.installId);
        }
        enqueue(builder.build(), callback, body -> gson.fromJson(body, GameDetail.class));
    }

    /**
     * {@code POST /plugins/rfl/games/join} — finds and joins the active game with this
     * passphrase; leaves any other game first.
     *
     * @param passphrase passphrase to look up
     * @param callback   receives the joined game's id, or a one-line error ("No game with that
     *                   passphrase" when none matches)
     */
    public void joinByPassphrase(final String passphrase, final Consumer<Result<String>> callback)
    {
        withIdentity(callback, identity ->
        {
            final PassphraseBody body = new PassphraseBody(identity.rsn, identity.installId, passphrase);
            enqueue(postRequest(GAMES_PATH + "/join", body), r -> callback.accept(
                !r.isOk() && GAME_NOT_FOUND.equals(r.error()) ? Result.error(NO_SUCH_PASSPHRASE) : r), this::readId);
        });
    }

    /**
     * {@code POST /plugins/rfl/games/:id/join}.
     *
     * @param id         game id
     * @param passphrase passphrase to confirm
     * @param callback   receives the game's id, or a one-line error ("Wrong passphrase" on 403)
     */
    public void join(final String id, final String passphrase, final Consumer<Result<String>> callback)
    {
        withIdentity(callback, identity ->
        {
            final PassphraseBody body = new PassphraseBody(identity.rsn, identity.installId, passphrase);
            enqueue(postRequest(GAMES_PATH + "/" + id + "/join", body), callback, this::readId);
        });
    }

    /**
     * {@code POST /plugins/rfl/games/:id/leave}.
     *
     * @param id       game id
     * @param callback receives success, or a one-line error
     */
    public void leave(final String id, final Consumer<Result<Void>> callback)
    {
        withIdentity(callback, identity ->
        {
            final LeaveBody body = new LeaveBody(identity.rsn, identity.installId);
            enqueue(postRequest(GAMES_PATH + "/" + id + "/leave", body), callback, ignored -> null);
        });
    }

    /**
     * {@code POST /plugins/rfl/games/:id/host} — host-only. {@code action} carries the action
     * name and its fields, e.g. {@code {"action": "assign", "rsn": "Foo", "team": "A"}}; the
     * caller's install id is merged in here so {@code RflPlugin} never has to repeat it.
     *
     * @param id       game id
     * @param action   action name plus its fields (spec §4)
     * @param callback receives success, or a one-line error (403 if not the host)
     */
    public void host(final String id, final Map<String, Object> action, final Consumer<Result<Void>> callback)
    {
        withIdentity(callback, identity ->
        {
            final Map<String, Object> body = new HashMap<>(action);
            body.put("installId", identity.installId);
            enqueue(postRequest(GAMES_PATH + "/" + id + "/host", body), callback, ignored -> null);
        });
    }

    /**
     * Reads the current identity and either runs {@code withIdentity}, or fails the callback
     * fast with {@link #NOT_READY} if no identity has been supplied yet.
     */
    private <T> void withIdentity(final Consumer<Result<T>> callback, final Consumer<Identity> withIdentity)
    {
        final Identity identity = identitySupplier.get();
        if (identity == null)
        {
            callback.accept(Result.error(NOT_READY));
            return;
        }
        withIdentity.accept(identity);
    }

    private Request postRequest(final String path, final Object body)
    {
        return new Request.Builder()
            .url(ReportSender.BASE_URL + path)
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .build();
    }

    private String readId(final String rawBody)
    {
        final IdBody parsed = gson.fromJson(rawBody, IdBody.class);
        return parsed == null ? null : parsed.id;
    }

    /**
     * Shared send/parse/error-map plumbing. Never blocks the calling thread: the request is
     * handed to OkHttp's own dispatcher, and every outcome — success, non-2xx, malformed body,
     * or no connection at all — ends in exactly one {@code callback.accept} call, so a caller
     * (the panel's poller, Task 6) is never left hanging (Review Focus item 5).
     */
    private <T> void enqueue(final Request request, final Consumer<Result<T>> callback,
        final Function<String, T> parseBody)
    {
        client.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(final Call call, final IOException e)
            {
                callback.accept(Result.error(NETWORK_ERROR));
            }

            @Override
            public void onResponse(final Call call, final Response response)
            {
                // Any failure reading/parsing becomes an error Result; the callback runs exactly once,
                // outside the try, so an exception thrown by the callback itself isn't reported twice.
                Result<T> result;
                try
                {
                    final String rawBody = response.body() == null ? "" : response.body().string();
                    result = response.isSuccessful()
                        ? Result.ok(parseBody.apply(rawBody))
                        : Result.error(friendlyError(apiErrorMessage(rawBody, response.code())));
                }
                catch (final IOException | RuntimeException e)
                {
                    result = Result.error(NETWORK_ERROR);
                }
                finally
                {
                    response.close();
                }
                callback.accept(result);
            }
        });
    }

    /**
     * @param rawBody response body text
     * @param status  HTTP status, used only for the fallback message if the body isn't the
     *                usual {@code {"error": "..."}} shape
     * @return the API's {@code error} field, or a generic status-coded message
     */
    private String apiErrorMessage(final String rawBody, final int status)
    {
        try
        {
            final JsonObject json = gson.fromJson(rawBody, JsonObject.class);
            if (json != null && json.has("error") && json.get("error").isJsonPrimitive())
            {
                return json.get("error").getAsString();
            }
        }
        catch (final JsonSyntaxException ignored)
        {
            // Not JSON (or not an object) — fall through to the generic message below.
        }
        return "RFL API error (" + status + ")";
    }

    /**
     * Normalizes the two API error strings task-4-brief.md calls out by name; every other
     * error (404 not found, 429 rate limited, host-only 403, etc.) is shown exactly as the API
     * phrased it.
     *
     * @param apiError the server's own {@code error} message
     * @return "Wrong passphrase" / "Passphrase already in use" for those two, else {@code apiError}
     */
    private static String friendlyError(final String apiError)
    {
        if ("wrong passphrase".equalsIgnoreCase(apiError))
        {
            return "Wrong passphrase";
        }
        if ("passphrase already in use".equalsIgnoreCase(apiError))
        {
            return "Passphrase already in use";
        }
        return apiError;
    }

    /** RSN, install id and world as of the moment a {@link GameClient} call is made. */
    public static final class Identity
    {
        public final String rsn;
        public final String installId;
        public final int world;

        public Identity(final String rsn, final String installId, final int world)
        {
            this.rsn = rsn;
            this.installId = installId;
            this.world = world;
        }
    }

    /** A successful value, or a one-line error message — never both. */
    public static final class Result<T>
    {
        private final T value;
        private final String error;

        private Result(final T value, final String error)
        {
            this.value = value;
            this.error = error;
        }

        public static <T> Result<T> ok(final T value)
        {
            return new Result<>(value, null);
        }

        public static <T> Result<T> error(final String message)
        {
            return new Result<>(null, message);
        }

        public boolean isOk()
        {
            return error == null;
        }

        /** @return the success value; meaningless when {@link #isOk()} is false */
        public T value()
        {
            return value;
        }

        /** @return the one-line error message, or null when {@link #isOk()} is true */
        public String error()
        {
            return error;
        }
    }

    private static final class ListBody
    {
        List<GameSummary> games;
    }

    private static final class IdBody
    {
        String id;
    }

    private static final class CreateBody
    {
        final String rsn;
        final String installId;
        final String name;
        final String passphrase;
        final int world;

        CreateBody(final String rsn, final String installId, final String name, final String passphrase,
            final int world)
        {
            this.rsn = rsn;
            this.installId = installId;
            this.name = name;
            this.passphrase = passphrase;
            this.world = world;
        }
    }

    private static final class PassphraseBody
    {
        final String rsn;
        final String installId;
        final String passphrase;

        PassphraseBody(final String rsn, final String installId, final String passphrase)
        {
            this.rsn = rsn;
            this.installId = installId;
            this.passphrase = passphrase;
        }
    }

    private static final class LeaveBody
    {
        final String rsn;
        final String installId;

        LeaveBody(final String rsn, final String installId)
        {
            this.rsn = rsn;
            this.installId = installId;
        }
    }
}
