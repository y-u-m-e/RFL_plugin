package com.rfl.game;

/**
 * One row of {@code GET /plugins/rfl/games}: an active (lobby or live) hosted game, without its
 * passphrase or roster. Field names match the API's JSON keys verbatim (see
 * {@code docs/superpowers/specs/2026-10-01-rfl-game-browser-design.md} §4), so an injected Gson
 * deserializes this with no {@code @SerializedName} needed — the same approach {@code RflReport}
 * uses for the outgoing side.
 */
public final class GameSummary
{
    public String id;
    public String name;
    public String hostRsn;
    public int world;
    public String state;
    public int playerCount;
    public long createdAt;

    /** No-arg constructor for Gson. */
    public GameSummary()
    {
    }

    public GameSummary(final String id, final String name, final String hostRsn, final int world,
        final String state, final int playerCount, final long createdAt)
    {
        this.id = id;
        this.name = name;
        this.hostRsn = hostRsn;
        this.world = world;
        this.state = state;
        this.playerCount = playerCount;
        this.createdAt = createdAt;
    }
}
