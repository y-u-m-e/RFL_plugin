# RFL game browser: hosted games in the plugin

Date: 2026-10-01
Status: approved design, pending implementation plan
Scope: `RFL_plugin` (new sidebar panel) and `yume-api` (`/plugins/rfl/games/*`, `rfl-db`).
rfl.gg changes are out of scope for now.
Builds on: `2026-09-25-rfl-audit-design.md` (reports, contacts, matches).

## 1. Purpose

Replace the typed Match code and Team settings with hosted games, like RuneLite's Party plugin:
a player hosts a game with a passphrase, others join by typing the passphrase or by picking it
from a list of active games, and the host assigns teams. Not everyone in the house is playing,
so spectators must not be flagged.

## 2. Decisions

| Question | Decision |
|---|---|
| What joining decides | The hosted game is the match: its name, roster and teams |
| Spectators | Ignored. A non-member is flagged only for a contact with a member (`nonmember_contact`) |
| Start / end | Host presses Start and End; server ends a game whose host has been silent for 10 min |
| Teams | Two teams; host assigns players and sets each team's name and colour |
| Transport | REST + polling on `yume-api`: list every 10 s while not in a game, lobby every 1.8 s in a game |
| Joining | By passphrase alone (Party-style) or from the list; passphrases are unique among active games |
| Host identity | The game's host install ID (private since the API stopped exposing it) |
| Opt-in | Panel makes no requests unless Enable reporting is on (Hub rule) |
| Match code / Team settings | Removed; the report carries `gameId` instead of `matchCode` |

## 3. Storage (`rfl-db`)

`rfl_games`: `id`, `name` (≤ 32 chars), `host_rsn`, `host_install_id`, `passphrase_hash` (salted
SHA-256 with a per-game salt; never returned), `passphrase_key` (normalised passphrase digest used
only for the unique lookup), `world`, `state` (`lobby` | `live` | `ended`), `team_a_name`,
`team_a_color`, `team_b_name`, `team_b_color`, `created_at`, `started_at`, `ended_at`,
`host_seen_at`.

Defaults: team A **Red** `#D9363E`, team B **Blue** `#2F6FDE`. Colours are `#RRGGBB`.

`rfl_game_players`: `game_id`, `rsn`, `install_id`, `team` (`A` | `B` | `''` unassigned),
`joined_at`, `left_at`, `removed` (0/1). Primary key (`game_id`, `rsn`).

Passphrases are trimmed and lower-cased before hashing and lookup.

## 4. API (public plugin host, no auth, identity = RSN + install ID as for reports)

| Route | Body / result |
|---|---|
| `GET /plugins/rfl/games` | Active games: `id, name, hostRsn, world, state, playerCount, createdAt` |
| `POST /plugins/rfl/games` | `{rsn, installId, name, passphrase, world}` → `{id}`. Host auto-joins. One active hosted game per install. 409 if the passphrase is in use by an active game |
| `GET /plugins/rfl/games/:id` | `{game, teams: [{key, name, color}], players: [{rsn, team, joinedAt}]}` (no passphrase, no install IDs) |
| `POST /plugins/rfl/games/join` | `{rsn, installId, passphrase}` → `{id}` of the active game with that passphrase; 404 if none |
| `POST /plugins/rfl/games/:id/join` | `{rsn, installId, passphrase}` → 200; 403 on a wrong passphrase; 403 if removed |
| `POST /plugins/rfl/games/:id/leave` | `{rsn, installId}` |
| `POST /plugins/rfl/games/:id/host` | `{installId, action, ...}`; actions: `assign {rsn, team}`, `remove {rsn}`, `team {key, name?, color?}`, `passphrase {passphrase}`, `start`, `end`. 403 unless `installId` is the host's |

Limits: body ≤ 4 KB; strings ≤ 32 chars (passphrase ≤ 64); one join attempt per install per 2 s,
and after 5 wrong passphrases on a game, that install waits 60 s; host actions one per install per
0.5 s. Malformed → 400.

Host liveness: any request from the host's install (poll, report, action) updates `host_seen_at`.
A game with `host_seen_at` older than 10 minutes is ended on the next request that touches it
(no cron; staging has none).

## 5. Reports and contacts

- Report field `gameId` (string, may be empty) replaces `matchCode`; `team` is removed from the
  report (the host's assignment is the source of truth). `v` stays 1; the plugin is unreleased.
- The server accepts `gameId` only when that RSN is a joined, non-removed member of that game;
  otherwise it is treated as empty.
- While a game is `live`, contacts and interceptions between two members inside the Start–End
  window are stored with the game's ID as their match. A live game is its own match; automatic
  match grouping continues for play outside hosted games.
- A contact between a member and a non-member during a live game writes a `nonmember_contact`
  event naming both. Sub-threshold grazes are not contacts, so spectators brushing past are not
  flagged.

## 6. Plugin panel

Main sidebar panel **RFL** (football icon), separate from RFL Debug.

Not in a game: **Host game** (asks for a name; offers a generated three-word passphrase such as
`brave-otter-lamp`, editable) and **Join game** (asks for a passphrase), then **Active games**
refreshed every 10 s; clicking one asks for its passphrase.

In a game: game name, state and passphrase with a copy button; roster in two columns coloured by
team plus Unassigned, own name marked; **Leave game**. Refresh every 1.8 s.

Host only: per-player team dropdown and Remove; rename and recolour each team (colour picker);
change passphrase; **Start** in the lobby, **End** while live.

All requests run off the client thread via the injected OkHttpClient. Errors (wrong passphrase,
passphrase taken, network) show as a one-line message. With Enable reporting off, the panel shows
"Turn on Enable reporting to browse and join games" and makes no requests.

## 7. Testing

- API (vitest): passphrase hashing and never returned; lookup by passphrase; uniqueness among
  active games; host-only actions rejected for others; join rate limit and wrong-passphrase
  back-off; auto-close after 10 min host silence; `gameId` accepted only for joined, non-removed
  members; member contacts tied to the game only inside the live window; `nonmember_contact`;
  team name and colour validation.
- Plugin (JUnit): lobby state model, passphrase generator, poll intervals.
- In game: host on one account, join on another by passphrase and from the list, assign teams and
  colours, start, collide, end.

## 8. Known limits

- Identity is RSN + install ID, as everywhere in RFL; a modified client can impersonate. Accepted.
- Polling every 1.8 s is ~11 requests/s for a 20-player game; fine for the Worker, revisit with a
  push channel if games grow large.
- rfl.gg does not show games yet.
