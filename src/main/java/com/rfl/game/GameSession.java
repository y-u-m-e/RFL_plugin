package com.rfl.game;

import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Singleton;

/**
 * The plugin's view of "the game I'm in, if any" — the last {@link GameDetail} seen from a
 * successful lobby poll, join, or create. Thread-safe: a lobby poll response lands on an OkHttp
 * callback thread while the panel reads it from the Swing EDT, and the report heartbeat reads
 * {@link #gameId()} on the client thread.
 */
@Singleton
public final class GameSession
{
    private final AtomicReference<GameDetail> current = new AtomicReference<>();

    /**
     * @return the current game's id, or {@code ""} when not in a game
     */
    public String gameId()
    {
        final GameDetail detail = current.get();
        return detail == null || detail.game == null ? "" : detail.game.id;
    }

    /**
     * @return the last {@link GameDetail} seen, or {@code null} when not in a game
     */
    public GameDetail detail()
    {
        return current.get();
    }

    /**
     * @param rsn an RSN to check
     * @return true only when in a game and {@code rsn} is that game's host
     */
    public boolean isHost(final String rsn)
    {
        final GameDetail detail = current.get();
        return detail != null && detail.game != null && rsn != null && rsn.equals(detail.game.hostRsn);
    }

    /**
     * Records a successful lobby poll, join, or create.
     *
     * @param detail the latest game state
     */
    public void update(final GameDetail detail)
    {
        current.set(detail);
    }

    /**
     * Leaves the game. Also used after being removed from a game or switching accounts, so a
     * removed player's reports go back to an empty {@code gameId}.
     */
    public void clear()
    {
        current.set(null);
    }
}
