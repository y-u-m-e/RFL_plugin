# RFL Game Browser Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Players host RFL games with a passphrase, others join by passphrase or from a polled list in an RFL sidebar panel, the host assigns teams (name + colour) and starts/ends the game, and reports tie contacts to the joined game.

**Architecture:** `yume-api` gets `/plugins/rfl/games/*` (pure validation in `rflGamesLogic.js`, D1 work in `rflGames.js`, `rfl-db`) and the report swaps `matchCode`/`team` for `gameId`. The plugin gets an HTTP client, a session model, a poller and a Swing panel.

**Tech Stack:** Cloudflare Workers + D1 + Vitest (`yume-api`, branch `dev`) · Java 11 RuneLite plugin, OkHttp + Gson (injected), JUnit 4 (`RFL_plugin`, branch `master`).

**Spec:** `docs/superpowers/specs/2026-10-01-rfl-game-browser-design.md` (this repo). Read it before any task.

## Global Constraints

- `yume-api`: work on `dev`, never push `main`; run node/npm only inside `webpage/apis/yume-api`; tests `npx vitest run <files>`, full `npm test`, `npm run lint`; push `dev` and watch CI to green (`gh run list --branch dev --limit 3`, `gh run watch <id> --exit-status`).
- `RFL_plugin`: work on `master`; `./gradlew build` must exit 0 before every commit (check the exit status explicitly).
- Never run `find /`, `find ~` or any filesystem-wide search (network drive).
- Hub rules: injected `OkHttpClient` and `Gson` only, no IO on the client thread, no reflection.
- Panel makes no requests unless `enableReporting` is on; message: `Turn on Enable reporting to browse and join games`.
- Polling: list every 10 s while not in a game; lobby every 1.8 s while in one.
- Limits (spec §4): body ≤ 4 KB; strings ≤ 32 chars, passphrase ≤ 64; one join attempt per install per 2 s; after 5 wrong passphrases on a game that install waits 60 s; host actions one per install per 0.5 s; malformed → 400.
- Passphrase: trimmed + lower-cased before hashing and lookup; salted SHA-256 per game; never returned; unique among active (`lobby`/`live`) games → 409 on clash.
- Team defaults: A **Red** `#D9363E`, B **Blue** `#2F6FDE`; colours match `^#[0-9A-Fa-f]{6}$`.
- Host liveness: any request from the host install updates `host_seen_at`; a game whose host has been silent > 600000 ms ends on the next request touching it.
- Generated passphrase: three lowercase words joined by `-` (e.g. `brave-otter-lamp`).
- yume-api: bump `version.json` + `package.json` minor once (Task 1) and add one `CHANGELOG.pending.md` bullet; do not cut the changelog.

## Review Focus

1. **The host presses Leave** instead of End: hosting passes to the remaining member who joined earliest (host RSN and install ID move to them); the game ends only if nobody is left → test in Task 2.
2. **A player joins a second game while still in one:** they must leave the first automatically, so they are never on two rosters → test in Task 2.
3. **A passphrase from an ended game is reused:** joining by it must not find the ended game, and a new game may take it → test in Task 2.
4. **A removed player keeps sending reports with the game's ID:** their `gameId` is treated as empty, their contacts are not the game's → test in Task 3.
5. **The API is unreachable while the panel is open:** the panel keeps the last state, shows the one-line error, and keeps polling without freezing the client → test in Task 4 (poller) and Task 6 (manual).

---

## Part A — `yume-api` (branch `dev`)

### Task 1: Game validation and passphrase hashing (pure)

**Files:**
- Create: `src/lib/rflGamesLogic.js`
- Test: `src/lib/rflGamesLogic.test.js`

**Interfaces:**
- Produces: `GAME_LIMITS = { maxBody: 4096, maxString: 32, maxPassphrase: 64, joinIntervalMs: 2000, wrongPassphraseLimit: 5, wrongPassphraseWaitMs: 60000, hostActionIntervalMs: 500, hostSilenceMs: 600000 }`; `TEAM_DEFAULTS = { A: { name: 'Red', color: '#D9363E' }, B: { name: 'Blue', color: '#2F6FDE' } }`; `normalizePassphrase(p) → string`; `async hashPassphrase(normalized, salt) → hex`; `async passphraseKey(normalized) → hex` (unsalted SHA-256, used only for the unique lookup); `newSalt() → hex` (16 random bytes); `validateCreate(body)`, `validateJoin(body)`, `validateHostAction(body)` → `{ ok: true, value } | { ok: false, error }`; `isColor(s) → boolean`.
- Host actions: `assign {rsn, team ∈ 'A'|'B'|''}`, `remove {rsn}`, `team {key ∈ 'A'|'B', name?, color?}`, `passphrase {passphrase}`, `start`, `end`.

- [ ] **Step 1:** Failing tests: `normalizePassphrase('  Brave-Otter-Lamp ')` → `'brave-otter-lamp'`; same passphrase + different salts → different hashes; same passphrase → same `passphraseKey`; `validateCreate` rejects a 33-char name, a 65-char passphrase, a missing installId; `validateHostAction` rejects an unknown action, team `'C'`, colour `'red'` and accepts `{action:'team', key:'A', color:'#00ff00'}`.
- [ ] **Step 2:** `npx vitest run src/lib/rflGamesLogic.test.js` → FAIL.
- [ ] **Step 3:** Implement with `crypto.subtle` (SHA-256 over `salt + ':' + normalized`).
- [ ] **Step 4:** Same command → PASS; bump version minor; changelog bullet `RFL: hosted games — host with a passphrase, join by passphrase or from the list, host-assigned teams with colours.`
- [ ] **Step 5:** Commit `Add RFL game validation and passphrase hashing`.

### Task 2: Game storage and routes

**Files:**
- Create: `src/lib/rflGames.js`
- Modify: `src/lib/rfl.js` (schema: `rfl_games`, `rfl_game_players` per spec §3, via the existing `createRflSchema` + guarded-ALTER pattern), `src/routes/publicApi.js` (routes + `/plugins` index entries)
- Test: `test/rfl-games.spec.js`

**Interfaces:**
- Consumes: Task 1.
- Produces (each `→ { status, body }`, `now` injectable for tests): `listGames(env, now)`, `createGame(request, env, now)`, `getGame(env, id, now)`, `joinByPassphrase(request, env, now)`, `joinGame(request, env, id, now)`, `leaveGame(request, env, id, now)`, `hostAction(request, env, id, now)`; `activeMembership(db, gameId, rsn) → { game, player } | null` (joined, not removed, game not ended) for Task 3; `touchHost(db, installId, now)`.
- Rulings beyond the spec (confirmed by the user): host **Leave** passes hosting to the remaining member who joined earliest, ending the game only if nobody remains; joining any game first leaves the player's current active game; an ended game frees its passphrase for reuse; the auto-close check runs in every function above before acting.
- Response shapes exactly as spec §4 (`GET /:id` → `{ game: {id, name, hostRsn, world, state, createdAt, startedAt, endedAt}, teams: [{key, name, color}], players: [{rsn, team, joinedAt}] }`).

- [ ] **Step 1:** Failing route tests (requests to `https://dev-api.ironforged.gg/plugins/rfl/games...`): create → 200 with id, host on roster, defaults Red/Blue; second create by the same install while active → 409; create with a passphrase in use → 409; list shows it; `GET /:id` body never contains `passphrase`, `salt` or `installId`; join by passphrase (mixed case, spaces) → that id; wrong passphrase → 403, six wrong in a row → 429 until 60 s pass; non-host `assign` → 403; host `assign`/`team`/`start`/`end` work and change state; removed player re-joining → 403; host silent 600001 ms → next `GET` shows `ended`; Review Focus 1–3 (host leave passes hosting to the earliest-joined remaining member, and ends the game when the host was alone; joining game B leaves game A; ended game's passphrase can be reused and join-by-passphrase does not find the ended game).
- [ ] **Step 2:** `npx vitest run test/rfl-games.spec.js` → FAIL.
- [ ] **Step 3:** Implement. Rate limits: keep per-install timestamps/counters in a small `rfl_game_limits` table (install_id, game_id, last_join_at, wrong_count, wrong_until, last_host_action_at).
- [ ] **Step 4:** Same command → PASS; `npm test`, `npm run lint` clean.
- [ ] **Step 5:** Commit `Add RFL hosted game routes`.

### Task 3: Reports carry `gameId`

**Files:**
- Modify: `src/lib/rflLogic.js` (`validateReport`), `src/lib/rfl.js` (`handleRflReport`)
- Test: `src/lib/rflLogic.test.js`, `test/rfl.spec.js`

**Interfaces:**
- Consumes: `activeMembership`, `touchHost` (Task 2).
- Report: required string `gameId` (≤ 64, may be empty) replaces `matchCode` and `team` (remove them from validation; existing columns stay, written empty).
- Rules (spec §5): `gameId` counts only if `activeMembership` returns a player; while that game is `live` and the event time is inside `[started_at, ended_at or now]`, a contact/interception event between two members is stored with `match_id = gameId`; a contact between a member and a non-member writes a `nonmember_contact` event (`a`, `b` sorted, `observer_rsn` = reporter, `match_id = gameId`); reports from the host install call `touchHost`.

- [ ] **Step 1:** Failing tests: `validateReport` rejects a missing `gameId`, accepts `''`; two members' `contact_start` during live → stored with `match_id` = game id; same before Start → not the game's; member + non-member contact during live → one `nonmember_contact`; Review Focus 4: a removed player's report with the game's id → their contact not stored under the game; host report updates `host_seen_at`.
- [ ] **Step 2:** `npx vitest run src/lib/rflLogic.test.js test/rfl.spec.js` → FAIL.
- [ ] **Step 3:** Implement; update existing tests that sent `matchCode`/`team`.
- [ ] **Step 4:** Same command → PASS; `npm test`, `npm run lint` clean.
- [ ] **Step 5:** Commit `RFL reports carry gameId`; push `dev`; watch CI green; smoke `curl -s -o /dev/null -w "%{http_code}" https://dev-api.ironforged.gg/plugins/rfl/games` → 200.

---

## Part B — `RFL_plugin` (branch `master`)

### Task 4: Game client, session model, poller, passphrase generator

**Files:**
- Create: `src/main/java/com/rfl/game/GameClient.java`, `GameSummary.java`, `GameDetail.java`, `GameSession.java`, `GamePoller.java`, `Passphrases.java`
- Test: `src/test/java/com/rfl/game/GameSessionTest.java`, `GamePollerTest.java`, `PassphrasesTest.java`

**Interfaces:**
- Produces: `GameClient` (@Singleton, injects `OkHttpClient`, `Gson`; base URL `ReportSender.BASE_URL`) with async methods taking a `Consumer<Result<T>>`: `list`, `create(name, passphrase)`, `get(id)`, `joinByPassphrase(passphrase)`, `join(id, passphrase)`, `leave(id)`, `host(id, Map<String,Object> action)`; RSN/installId/world supplied by a `Supplier<Identity>` set from `RflPlugin` (read on the client thread). `GameSession` (thread-safe): `String gameId()` (empty when none), `GameDetail detail()`, `boolean isHost(String rsn)`, `void update(GameDetail)`, `void clear()`. `GamePoller`: `long nextDelayMs(boolean inGame)` → 1800 in a game, 10000 otherwise; keeps the last successful data on failure and exposes `String lastError()`. `Passphrases.generate(Random)` → three words from a bundled list (≥ 200 short common words in `src/main/resources/com/rfl/passphrase-words.txt`) joined by `-`.

- [ ] **Step 1:** Failing tests: poller delays 1800/10000; after a failed poll `lastError()` is set and the previous detail is still returned (Review Focus 5); `Passphrases.generate(new Random(1))` matches `^[a-z]+-[a-z]+-[a-z]+$` and differs for two seeds; `GameSession.isHost` true only for the host RSN; `clear()` empties `gameId`.
- [ ] **Step 2:** `./gradlew test --tests 'com.rfl.game.*'` → FAIL.
- [ ] **Step 3:** Implement. `Result<T>` = success value or one-line error (`"Wrong passphrase"` on 403 from join, `"Passphrase already in use"` on 409, `"Can't reach the RFL API"` on network failure, else the API's `error`).
- [ ] **Step 4:** Same command → PASS; `./gradlew build` exit 0.
- [ ] **Step 5:** Commit `Add RFL game client, session and poller`.

### Task 5: Report sends `gameId`; remove Match code and Team settings

**Files:**
- Modify: `src/main/java/com/rfl/RflReport.java`, `RflPlugin.java`, `RflConfig.java`
- Test: `src/test/java/com/rfl/RflReportTest.java`

**Interfaces:**
- Consumes: `GameSession.gameId()` (Task 4).
- `RflReport`: field `gameId` (string, empty when none) replaces `matchCode` and `team`; remove the `matchCode`/`team` config items and the Match section.

- [ ] **Step 1:** Failing test: serialized report has `"gameId"` and no `matchCode`/`team` keys.
- [ ] **Step 2:** `./gradlew test --tests com.rfl.RflReportTest` → FAIL.
- [ ] **Step 3:** Implement; RflPlugin passes `gameSession.gameId()`.
- [ ] **Step 4:** `./gradlew build` exit 0.
- [ ] **Step 5:** Commit `Report gameId instead of match code and team`.

### Task 6: RFL sidebar panel

**Files:**
- Create: `src/main/java/com/rfl/game/GamePanel.java`
- Modify: `src/main/java/com/rfl/RflPlugin.java` (NavigationButton "RFL" with the football icon from `DebugPanel.icon()`, always present while the plugin runs; start/stop the poller on a `ScheduledExecutorService` with `GamePoller.nextDelayMs`)

**Interfaces:**
- Consumes: Tasks 4–5.
- Layout per spec §6. Not in a game: **Host game** (dialog: name, passphrase pre-filled from `Passphrases.generate`), **Join game** (dialog: passphrase), **Active games** list (click → passphrase dialog). In a game: name, state, passphrase + **Copy**, roster columns tinted with team colours plus Unassigned, own name marked, **Leave game**. Host only: per-player team combo + **Remove**, per-team name field + colour button (`JColorChooser`), **Change passphrase**, **Start** (lobby) / **End** (live). Errors show in one label. With reporting off: only the opt-in message, poller stopped.
- All Swing work on the EDT; all network via `GameClient`; never touch client state from the EDT (identity snapshot comes from the client thread).

- [ ] **Step 1:** Implement the panel and wiring.
- [ ] **Step 2:** `./gradlew build` exit 0.
- [ ] **Step 3:** Commit `Add RFL game browser panel`; push `master`.
- [ ] **Step 4:** In-game check (user): host on one account, join on another by passphrase and from the list, assign teams and colours, start, collide, end; unplug network briefly to see the error line and recovery (Review Focus 5).
