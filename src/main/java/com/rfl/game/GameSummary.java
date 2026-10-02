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
}
