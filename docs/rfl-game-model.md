# RFL game model: rules, movement and collision

Reference for modelling RuneScape Football League (RFL) plays. Written 2026-10-02 from the
`RFL_plugin` code (master), the yume-api RFL code (dev, 2.31.0) and the design decisions made
while building them.

Each fact is tagged:

| Tag | Meaning |
|---|---|
| **[code]** | Enforced by our plugin or server today. Authoritative. |
| **[decided]** | A rule the league owner stated. |
| **[osrs-high]** | Well-established OSRS mechanic. |
| **[osrs-verify]** | My best understanding of OSRS. Measure it before you rely on it (see §6). |
| **[gap]** | Not defined anywhere yet. Needs the league owner. |

---

## 1. League rules we have

### The pitch

- **[code]** Games are played inside a **player-owned house (POH)**, which is an instance.
  - Detected by the POH template regions 7257, 7534, 7535, 7790, 7791, 8046, 8047, 8302 and 8303 (RuneLite's Roof Removal list).
  - Positions use `WorldPoint.fromLocalInstance`, so every player in the same house gets the same template coordinates.
- **[decided]** The field is **16 columns × 40 rows** of tiles, numbered from 1.
  - **Endzones:** rows 1 and 40.
  - **Out of bounds:** columns 1 and 16.
  - **In play:** columns 2–15, rows 2–39 (14 × 38).
  - **Corners** (columns 1 and 16 on rows 1 and 40) are out-of-bounds columns, but entering one **directly from an in-play tile** counts as a touchdown. A diagonal step from (2, 2) into (1, 1) is an example. Entering a corner from an out-of-bounds tile does not count.
- **[gap]** Where the field sits in the POH (template coordinates and which room or rooms), and which team attacks which endzone.
- **[gap]** A run moves 2 tiles per tick, so it can pass through an out-of-bounds tile on its way into a corner, for example (2, 3) → (1, 2) → (1, 1). Does that count as out of bounds or as a touchdown? The same question applies to running out of bounds and back in within one tick.

### The ball

- **[code]** The ball is a **handegg**. Any of three items count, all from Easter 2018:
  - Holy (`EASTER18_HANDEGG_LIGHT`)
  - Peaceful (`EASTER18_HANDEGG_BALANCE`)
  - Chaotic (`EASTER18_HANDEGG_CHAOS`)
- **[code]** Item ids are 22355, 22358 and 22361. A player "has the ball" when a handegg is in their **weapon slot**.
- **[code]** A thrown handegg exists as an in-flight projectile, one per type: spotanims `EASTER18_HANDEGG_TRAVEL_SARA`, `_GUTH` and `_ZAM` (1526, 1527 and 1528).
- **[gap]** Throw range, flight time per distance, and whether a throw can miss or land on a tile. Flight time is measurable from the projectile (§6).

### Teams

- **[decided] [code]** Each hosted game has:
  - Team A (default name Red, colour `#D9363E`), with a host-set name and colour
  - Team B (default name Blue, colour `#2F6FDE`), with a host-set name and colour
  - **Referees**, fixed name, drawn black and white striped
  - unassigned players
- **[decided]** Every league player must run the plugin.
- **[gap]** Players per side, substitutions, and what referees are allowed to do on the pitch.

### Collisions

- **[decided] [code]** A collision only matters when **at least one of the two players holds a handegg**. Touches with no ball involved are ignored.
- **[code]** A collision is detected from the **3D models touching**, not from tiles (§3).
- **[code]** Walking through someone counts as **one** collision. It starts on the first triangle touch and ends when the two models' bounding boxes separate.
- **[gap]** What a collision *means* in the game: a tackle, a down, a turnover? Does the carrier have to stop?

### Interceptions

- **[decided] [code]** Checked on exactly **one tick per throw**: the tick the in-flight handegg stops being drawn. On that tick, the interceptor is the player who meets all three conditions:
  - now has a handegg equipped
  - did **not** hold one before the throw
  - is colliding with another player on that same tick (models touching)
- **[code]** Hand-to-hand passes without a throw, and uncontested catches, are **not** interceptions.
- **[code]** Detected locally and shown in chat and as a tile highlight. Interceptions are not sent to the server yet.

### Fair play

- **[decided]** Banned plugins: **Block Tracker** and **True Tile Player Indicators**. rfl.gg reads the banned list from a config JSON. Unofficial plugins are allowed and shown in beige.
- **[code]** Each report includes the player's own plugin list (enabled and disabled) and logs toggles.

### Not defined yet

- **[gap]** Scoring, downs, game length, kickoff and restart, fouls and penalties, what referees rule on, and how a play starts and ends. These are the core rules a play optimiser needs.

---

## 2. How RuneScape movement works

### The server

- **[osrs-high]** The server runs in **ticks of 600 ms**. All movement is resolved on ticks. Between ticks nothing moves on the server.
- **[osrs-high]** **Walking** moves 1 tile per tick. **Running** moves 2 tiles per tick.
- **[osrs-high]** If a running player has 1 tile left, they move 1 tile that tick.
- **[osrs-high]** Players move in all 8 directions. A diagonal step is blocked if either adjacent cardinal tile is blocked, so there's no cutting corners past walls or furniture.
- **[osrs-high]** **Players do not block players.** Any number of players can stand on the same tile. The only physical contact in RFL is the model overlap our plugin measures; the game itself has none.
- **[osrs-high]** Clicking a tile makes the server path to it. If the tile is unreachable, the player goes to the nearest reachable tile.
- **[osrs-verify]** Pathfinding is a breadth-first search in a bounded area around the player. Neighbours are tried in the order W, E, S, N, SW, SE, NW, NE, so ties between equal-length paths break that way. Paths tend to go diagonal first, then straight.
- **[osrs-high]** A click takes effect on the next tick. Your input lands up to 600 ms later, plus your network latency.
- **[osrs-high]** **Following** another player moves you to the tile they stood on the **previous tick**, so a follower is always about one step behind.
- **[osrs-verify]** Each tick the server processes players in **PID order**, and PID is reshuffled periodically. That decides who "moves first" when two players act on the same tick, for example when one of them follows or reaches a moving target.

### The client: models are not on true tiles

The model you see is drawn at the client's **rendered position**. The server's tile is the **true tile**. The two differ, and that matters because collisions are measured on the models.

- **[osrs-high]** The client gets position updates once per tick. It does **not** predict movement; even your own character only moves after the server says so.
- **[osrs-verify]** The client runs at about 50 cycles per second (20 ms per cycle). Each cycle it moves the actor's rendered position toward the next queued tile at a fixed speed:
  - walking: about 4 local units per cycle, so 128 units (one tile) in about 0.6 s
  - running: about twice that
- **[osrs-verify]** If more steps are queued than normal (lag, or a burst of steps), the client **speeds up** to catch up. If an actor is too far behind, or teleports, it **snaps** to the tile.
- **[osrs-high]** As a result, the model **trails the true tile**: by up to about 1 tile while walking and up to about 2 while running. It lines up with the true tile only once the actor stops.
- **[osrs-high]** Each client sees every other player with **its own timing**, depending on its latency and when packets arrive. Two clients can disagree for a moment about whether two models touched. That's why the server pairs both players' reports within a time and distance window (§4).
- **[osrs-verify]** **Facing.** An actor faces its movement direction, or the entity it interacts with. The client turns the model toward a new direction at a limited rate per cycle, so during sharp direction changes the model's orientation lags too.
  - Orientation units are 0–2047 for a full turn; 0 = south.
- **[osrs-high]** **Animation.** The walk and run cycles swing arms and legs, so the model's shape changes every frame. A running stride reaches further than standing still. Idle poses differ again.

What causes the model to be offset from the true tile:
- movement in progress (always)
- running vs walking
- latency and packet timing on the observing client
- catch-up speed when steps queue up
- turning (orientation lag)
- the animation pose

The true tile is exactly what **True Tile Player Indicators** reveals. That's why it is banned: it shows where a player really is, ahead of their model.

---

## 3. How our collision detection works

- **[code]** **What's tested:** each player's **posed 3D model** for the current animation frame, built from the **bare body** by default. "Bare body" means the player's identity-kit body parts without equipment, so gear can't bulk up a hitbox. The worn-model source is a setting.
  - The model is placed at the rendered position (§2), rotated by the actor's orientation, with height measured from the soles.
- **[code]** **Test:** exact triangle-triangle intersection (Möller 1997) between two models.
  - Broad phase: whole-model bounding boxes, then per-triangle boxes with a sort-and-sweep pass.
  - Fully transparent and hidden faces are skipped.
- **[code]** **Contact:** starts when any triangles touch, and holds until the two models' bounding boxes separate.
- **[code]** **Overlap ("depth"):** the number of touching triangle pairs, capped at 512. It's unitless: higher means more overlap, not a distance.
- **[code]** **Where:** the tile under the centroid of the touching triangles.
- **[code]** **Cost:** mesh checks run on frames, with the overlap sampled once per tick. The debug panel shows milliseconds per frame.
- Consequence for modelling: a collision depends on the **rendered** models, which trail the true tiles. Two players can collide in the models while their true tiles are a tile apart, or pass on adjacent tiles without their models touching, depending on stride, turning and timing.

---

## 4. What the server does with it

- **[code]** **Self-only reporting:** each plugin reports only its own player (a Plugin Hub requirement).
  - A contact event carries `contactId`, time, tick, tile (`x`, `y`, `plane`), overlap, and `ball: self|other` (who held the handegg at the start).
  - It never names the other player.
- **[code]** **Bystander reports:** a plugin that sees two *other* players collide while one of them holds a handegg sends a name-free `collision_seen` (time and tile).
- **[code]** **Pairing:** the server pairs two players' contact starts into one collision when they are:
  - on the same world
  - on the same plane
  - within **2 tiles** (Chebyshev distance)
  - within **1200 ms** of each other, after correcting each report's clock as `at + (receivedAt − sentAt)`
- **[code]** Each start pairs with at most one other: nearest in time, then in distance.
- **[code]** If one player says `self` and the other says `other`, the collision is **carrier-confirmed**.
- **[code]** Bystander reports in the same window are counted as **witnesses**. A witness never creates a collision on its own.
- **[code]** An unpaired contact stays one-sided ("unconfirmed").
- **[code]** In a **live hosted game**, a collision belongs to the game when both players are members and it falls between Start and End.
- **[code]** **Observer mode** (being added): tracks every handegg collision in view, with both names, and saves it locally only. Nothing is sent.

---

## 5. Timing constants

| Constant | Value | Source |
|---|---|---|
| Game tick | 600 ms | osrs-high |
| Walk speed | 1 tile/tick | osrs-high |
| Run speed | 2 tiles/tick | osrs-high |
| Client cycle | ~20 ms | osrs-verify |
| Local units per tile | 128 | osrs-high |
| Full turn | 2048 orientation units | osrs-high |
| Pairing time window | 1200 ms (2 ticks) | code |
| Pairing distance window | 2 tiles | code |
| Overlap cap | 512 triangle pairs | code |
| Lobby refresh (in game) | 1.8 s | code |
| Host silence auto-end | 10 min | code |

---

## 6. Measuring the [osrs-verify] items

The plugin already has the hooks. A small debug addition can log, **per client frame** for every player in the house:
- the server tile (`WorldPoint`)
- the rendered `LocalPoint`
- orientation
- the current animation id and frame
- the mesh bounds

From two accounts walking and running set routes, that log gives:
- **trail distance:** the rendered position compared with the true tile, against time since the tick
- **client move speed, catch-up and snap thresholds**
- **turn rate:** orientation change per frame
- **stride reach:** mesh bounds per animation frame, walk vs run vs idle
- **handegg flight time vs distance:** frames between the projectile appearing and disappearing

Ask for "add a movement trace logger" and the plugin agent can add it behind a debug setting.
