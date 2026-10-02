package com.rfl.game;

/**
 * Poll cadence plus last-known-good state for one polling loop (the active-games list, or the
 * current lobby). Holds no network code and does no IO ({@link GamePanel} drives it from
 * {@link GameClient} callbacks), so it is plain, unit-testable logic. Thread-safe: written on
 * OkHttp threads, read on the EDT.
 *
 * <p>On a failed poll the previous data is kept and {@link #lastError()} is set instead of
 * cleared, so the panel can keep showing the last known state plus a one-line error rather than
 * freezing or going blank while the API is unreachable.
 *
 * @param <T> the polled data's type ({@code List<GameSummary>} or {@link GameDetail})
 */
public final class GamePoller<T>
{
    static final long GAME_DELAY_MS = 1800L;
    static final long LIST_DELAY_MS = 10000L;

    private volatile T lastData;
    private volatile String lastError = "";

    /**
     * @param inGame true while this poller is driving the 1.8 s lobby loop, false for the 10 s
     *               active-games list loop
     * @return 1800 while in a game, else 10000
     */
    public long nextDelayMs(final boolean inGame)
    {
        return inGame ? GAME_DELAY_MS : LIST_DELAY_MS;
    }

    /**
     * Records a successful poll: replaces the last data and clears any prior error.
     *
     * @param data the freshly polled data
     */
    public void onSuccess(final T data)
    {
        lastData = data;
        lastError = "";
    }

    /**
     * Records a failed poll: keeps the last successful data and sets the one-line error.
     *
     * @param error one-line error message (e.g. "Can't reach the RFL API")
     */
    public void onFailure(final String error)
    {
        lastError = error;
    }

    /**
     * @return the last successfully polled data, or {@code null} before the first success
     */
    public T data()
    {
        return lastData;
    }

    /**
     * @return the most recent poll's error, or {@code ""} when the last poll succeeded
     */
    public String lastError()
    {
        return lastError;
    }
}
