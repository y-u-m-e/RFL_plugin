# RFL audit: plugin reporting and contact detection

Date: 2026-09-25
Status: approved design, pending implementation plan
Scope: `RFL_plugin` (RuneLite) and the `/plugins/rfl/*` routes plus `rfl-db` in `yume-api`.
The rfl.gg site is out of scope and gets its own spec; it only reads what this API serves.

## 1. Purpose

RFL (RuneScape Football League) matches are played in player-owned houses (POHs). Refs need
two things they can't see today:

1. **Which plugins each player had enabled during a match**, so banned plugins can be spotted.
2. **When players made contact (tackles)**, defined as the *player models* intersecting — not
   true tiles overlapping, and not anything derived from the camera's point of view.

### Trust model (decided)

The league runs on player integrity. The system's job is to make honest play effortless and
cheating a deliberate, visible act — not to be tamper-proof. A determined cheater with a
modified client can defeat any client-side check; that is accepted.

The signal refs rely on is *absence and pattern*: a player who is visible in a match but not
reporting, or whose reports keep dropping out, is the flag. Because presence is also reported by
other players' clients, that signal doesn't depend on trusting the missing player.

Decisions made during design:

| Question | Decision |
|---|---|
| Local encrypted log vs server | **Server.** The plugin POSTs regularly; no local log. |
| Where the API lives | `/plugins/rfl/*` on the existing `yume-api` gateway |
| Storage | New D1: `rfl-db` (prod), `rfl-db-dev` (staging), binding `RFL_DB` |
| Player identity | **RSN only, no auth.** Plus a per-install ID to flag duplicate senders |
| When it runs | Plugin reporting whenever logged in; contact detection only inside a POH |
| Contact precision | Upright cylinder per player (radius from the unrotated model bounds, so turning doesn't grow it). Replaced the first axis-aligned boxes, which touched on diagonals in-game |
| Report interval | ~10 s |
| Site | New read-only site at rfl.gg; **everything public**, no login |

Consequence of "RSN only": data is as trustworthy as its sender. Any client can POST as any RSN.
The install-ID check and multi-observer corroboration are the mitigations; the spec does not
attempt more.

## 2. Architecture

```
RuneLite client (every player)                yume-api (gateway)             rfl.gg
┌────────────────────────────┐   POST /plugins/rfl/report   ┌──────────┐  GET   ┌──────────┐
│ PluginSnapshotter          │ ─────────── every 10 s ────▶ │ validate │ ◀───── │ read-only│
│ PohDetector                │                              │ group    │        │ viewer   │
│ ContactDetector (per frame)│                              │ rfl-db   │        └──────────┘
│ ReportSender (queue+OkHttp)│                              └──────────┘
└────────────────────────────┘
```

Matches are never created by hand. The server groups reporters who are on the same world, inside
a POH, and can see each other.

## 3. Plugin (`RFL_plugin`)

Package and config group move from `com.playercollision` / `playercollision` to `com.rfl` / `rfl`.
No user settings need preserving.

### Classes

| Class | Does |
|---|---|
| `RflPlugin` | Guice wiring, event subscriptions, lifecycle |
| `PluginSnapshotter` | Builds the enabled-plugin list (name + source: `BUILTIN`, `PLUGIN_HUB`, `UNOFFICIAL`) and its hash; emits `plugin_toggle` events on `PluginChanged` |
| `PohDetector` | Each game tick: in an instance whose template region is a POH region → `inPoh` |
| `ContactDetector` | Every client frame while `inPoh`: pairwise model-box test over visible players; emits `contact_start` / `contact_end` |
| `ReportSender` | Batches state + queued events, POSTs every 10 s via injected OkHttp, retries |

Existing code removed: local hash-chained session log, clipboard export/check, local blacklist
file, ref right-click player option, and the panel controls for them. Source detection
(`pluginSource`) and plugin enumeration (`collectEnabledPluginEntries`) are kept and moved into
`PluginSnapshotter`.

### Contact detection

- Runs on every client frame (not game tick — models interpolate between tiles within a 0.6 s
  tick, so a tick-rate check misses contacts mid-stride).
- For each visible player: the model's axis-aligned bounding box for its current animation frame
  and orientation (RuneLite `Model` AABB; confirm exact API at plan time), translated by the
  player's `LocalPoint` and height. Scene space only — the camera is never used.
- Overlap test lives in one pure static function:
  `static int overlapDepth(Box a, Box b)` → `0` for no contact, else the smallest-axis
  penetration in local units (128 = one tile). A cylinder test can replace it later without
  touching callers.
- Any pair in view is tested, not only pairs involving the local player (needed for
  corroboration). Pair names are sorted so every client reports a pair identically.
- State per pair: on `0 → >0` emit `contact_start` with `depth` (max depth is updated while
  in contact and sent on end); on `>0 → 0` emit `contact_end`.
- ~10 players → ≤45 pairs per frame of box math. Cheap; no broad-phase needed.

### Report sending

- Every 10 s while logged in, **even when nothing changed** — the POST is the heartbeat.
- POSTs run off the client thread through RuneLite's injected `OkHttpClient`.
- On failure, events stay queued and ride along with the next batch. Queue holds at most 5 minutes
  of events; oldest dropped beyond that. The server's `gap` event covers what was lost.
- Base URL: `https://dev-api.ironforged.gg` while developing, `https://api.ironforged.gg` for
  release — a constant, not a user setting.

### Write-only

The plugin only sends. It never reads anything back: no banned list, no match state, no
warnings. Judging plugins against the rules happens on rfl.gg.

### Config

The interval, URL, and detection are fixed so all players report identically. Settings:
`enableReporting` (master switch, off by default); under "Match", two free-text labels, both
empty by default — `matchCode` ("Match code": the code the league gave this game, e.g. `W3-G2`)
and `team` ("Team"); then three feature toggles under "Features", all on by default: `reportPlugins` (plugin list + `plugin_toggle` events), `reportContacts`
(contact detection), `reportNearby` (`seen`). A disabled feature sends an empty field and is
listed in every report's `features`; the server stores it and rfl.gg shows it as a flag —
turning a feature off is always a visible act. The hidden `installId` is the only other stored
value.

`matchCode` and `team` are sent on every report, trimmed and capped at 32 characters
(`matchCode` also uppercased). They are **labels, never grouping keys**: matches are still
formed by the server from world + POH + `seen` (§5), so changing your code cannot take you out
of a match — it only gets you flagged.

## 4. Report format

`POST /plugins/rfl/report`, JSON:

```json
{
  "v": 1,
  "rsn": "Some Player",
  "installId": "uuid generated on first run, stored in plugin config",
  "pluginVersion": "1.0.0",
  "world": 330,
  "sentAt": 1790000000000,
  "inPoh": true,
  "matchCode": "W3-G2",
  "team": "Red",
  "seen": ["Player B", "Player C"],
  "plugins": [{ "name": "GPU", "source": "BUILTIN" }],
  "features": { "plugins": true, "contacts": true, "nearby": true },
  "events": [
    { "at": 1789999998120, "tick": 51234, "type": "contact_start",
      "a": "Player B", "b": "Player C", "depth": 34 },
    { "at": 1789999998900, "tick": 51235, "type": "contact_end",
      "a": "Player B", "b": "Player C", "depth": 41 },
    { "at": 1789999996000, "tick": 51230, "type": "plugin_toggle",
      "plugin": "Some Plugin", "enabled": true }
  ]
}
```

- `plugins` is the full enabled list every batch (~5 KB). The server stores a snapshot only when
  its hash changes. No diffing, no resync protocol.
- `seen` is every other player the client can currently see; empty outside a POH is fine.
- `features` is required: exactly three booleans, true = enabled. Stored as `features_off`
  (sorted, comma-separated) on `rfl_players` (latest report) and `rfl_match_players` (union of
  everything reported off during that match).
- `matchCode` and `team` are required strings, empty allowed. The server trims both, uppercases
  `matchCode`, and truncates each to 32 characters (truncated, not rejected); a non-string is `400`.
- `at` / `sentAt` are client epoch ms. `tick` is `client.getTickCount()` — local to that client,
  only meaningful for ordering one observer's events.

### Time

The player's clock is not trusted. The server stamps `receivedAt` and computes each event's time
as `receivedAt - (sentAt - at)`. Only the ≤10 s gap between an event and its send depends on the
client clock. All times are stored as UTC epoch ms; rfl.gg renders them in the viewer's zone.

## 5. API (`yume-api`)

Routes, added to `src/routes/publicApi.js` alongside the existing `/plugins/*` handlers and listed
in the `/plugins` index:

| Route | Purpose |
|---|---|
| `POST /plugins/rfl/report` | The only write |
| `GET /plugins/rfl/matches` | Live and recent matches (newest 50), each with its `code`; optional `?code=W3-G2` filter |
| `GET /plugins/rfl/matches/:id` | `code`; players (reporting status, `matchCode`, `team`, `codeMismatch`, plugin snapshots), all events, corroborated contacts |
| `GET /plugins/rfl/players/:rsn` | A player's matches (each with the match's `code` and the player's `matchCode` / `team`), gaps, snapshots |

No auth on any route (decided). The API has no notion of banned plugins: it stores what was
enabled, and rfl.gg judges it against its own configuration JSON (see the rfl.gg spec).

### Validation and limits (trust boundary — not optional)

- Body ≤ 64 KB; `seen` ≤ 100; `events` ≤ 500; strings ≤ 64 chars; `v` must be `1`.
- Unknown `type`s are rejected, not stored.
- One report per `installId` per 5 s → `429`. Malformed → `400`.

### `rfl-db` tables

| Table | Holds | Written |
|---|---|---|
| `rfl_players` | rsn (PK), install_id, last_report_at, world, in_poh, seen_json, plugin_hash, features_off, match_code, team | upsert every report |
| `rfl_plugin_snapshots` | rsn, install_id, at, hash, plugins_json | only when hash changes |
| `rfl_matches` | id, world, started_at, last_active_at | group forms / updates |
| `rfl_match_players` | match_id, rsn, first_at, last_at, reporting (0/1), max_observers, features_off, match_code, team | every in-POH report |
| `rfl_events` | id, match_id, observer_rsn, at (server time), tick, type, a, b, depth, plugin, enabled | from batches + server |

Columns added after first deploy (`features_off`, `match_code`, `team`) are also added with a
guarded `ALTER TABLE ... ADD COLUMN` (duplicate-column errors ignored), so older tables upgrade in
place. `match_code` / `team` on `rfl_match_players` come from the reporter's latest in-POH report
in that match; seen-only rows keep `''`.

### Grouping (on write)

An in-POH report joins an open match (same world, `last_active_at` within 2 minutes) that already
contains the reporter or anyone in its `seen`. Otherwise a new match starts. Every RSN in `seen`
gets a `rfl_match_players` row; ones that never report keep `reporting = 0` and an observer count —
this is the "plugin off" flag.

### Server-generated events

- `gap`: a reporting player in an open match whose reports stop for > 25 s. Written when their
  next report arrives, with start and end. A player who never comes back needs no event: their
  `last_at` ending before the match's `last_active_at` shows it on read. (No cron needed, and
  staging has none.)
- `install_conflict`: two `installId`s report the same RSN within 25 s of each other.

### Match code (on read)

A match's `code` is the most common non-empty `match_code` among its players with
`reporting = 1`; a tie goes to the code whose player has the latest `last_at`; no codes → `null`.
Computed in SQL per request (one query for the list). A reporting player gets
`codeMismatch = true` when the match has a code and theirs differs — including an empty code
while others agree. Players who never report are never flagged. `?code=` on the list is trimmed
and uppercased before comparing against the computed code.

### Corroboration (on read)

`contact_start` events for the same pair from different observers, within ±1.2 s (two ticks) of
server time, are one contact. The response carries `observers` / `eligibleObservers` (reporting
players in that match at that time), rendered on rfl.gg as "seen by 4/6".

### Retention

Keep everything. Revisit when `rfl-db` size warrants it (D1 limit 10 GB).

## 6. Deploy and config

- `wrangler.jsonc`: `RFL_DB` binding → `rfl-db` (top level, prod), `rfl-db-dev` (staging env).
- Schema via `CREATE TABLE IF NOT EXISTS` at first use, matching `ensureDropInboxTable` style.
- Ships to staging on `dev` like any `yume-api` change. Prod only on explicit release.

## 7. Plugin Hub

The Hub listing and plugin description must state plainly that the enabled plugin list, RSN,
world, and names of nearby players are sent to `api.ironforged.gg` and published publicly on
rfl.gg. **Confirm the Hub's current third-party-data rule before submitting.**

## 8. Testing

- Plugin: JUnit tests for `overlapDepth` (touching, separated, contained, edge-only) and report
  JSON building.
- API: vitest specs in `test/` for validation limits, grouping (join vs new match, unreported
  seen players), `gap` and `install_conflict` generation, and corroboration windows.
- In-game: two accounts in a POH, walk into each other, confirm `contact_start` from both clients
  and "seen by 2/2" on the match endpoint.

## 9. Known limits

- A modified client can report anything. Accepted (integrity model).
- Box test over-reports when limbs extend the box; `depth` lets refs discount shallow contacts.
  Upgrade path: cylinder in `overlapDepth`.
- Client clock skew affects event times by at most the in-batch offset error.
- A crash and a deliberately killed client both produce a `gap`; refs judge the pattern.
