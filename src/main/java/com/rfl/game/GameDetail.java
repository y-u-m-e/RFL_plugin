package com.rfl.game;

import java.util.List;

/**
 * {@code GET /plugins/rfl/games/:id} response: the game, its two teams, and its current roster
 * (no passphrase, no install IDs). Field names match the API's JSON keys verbatim.
 */
public final class GameDetail
{
    public Game game;
    public List<Team> teams;
    public List<Player> players;

    /** No-arg constructor for Gson. */
    public GameDetail()
    {
    }

    public GameDetail(final Game game, final List<Team> teams, final List<Player> players)
    {
        this.game = game;
        this.teams = teams;
        this.players = players;
    }

    /** The game's own fields, as returned under the {@code game} key. */
    public static final class Game
    {
        public String id;
        public String name;
        public String hostRsn;
        public int world;
        public String state;
        public long createdAt;
        public Long startedAt;
        public Long endedAt;

        public Game()
        {
        }

        public Game(final String id, final String name, final String hostRsn, final int world,
            final String state, final long createdAt, final Long startedAt, final Long endedAt)
        {
            this.id = id;
            this.name = name;
            this.hostRsn = hostRsn;
            this.world = world;
            this.state = state;
            this.createdAt = createdAt;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
        }
    }

    /** One team slot (defaults: A Red {@code #D9363E}, B Blue {@code #2F6FDE}). */
    public static final class Team
    {
        public String key;
        public String name;
        public String color;

        public Team()
        {
        }

        public Team(final String key, final String name, final String color)
        {
            this.key = key;
            this.name = name;
            this.color = color;
        }
    }

    /** One roster entry; {@code team} is {@code ""} when unassigned. */
    public static final class Player
    {
        public String rsn;
        public String team;
        public long joinedAt;

        public Player()
        {
        }

        public Player(final String rsn, final String team, final long joinedAt)
        {
            this.rsn = rsn;
            this.team = team;
            this.joinedAt = joinedAt;
        }
    }
}
