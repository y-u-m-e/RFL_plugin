# RFL game model: rules, movement and collision

Reference for modelling RuneScape Football League (RFL) plays. Written 2026-10-02 from the
`RFL_plugin` code (master), the yume-api RFL code (dev, 2.31.0), the design decisions made
while building them, and the **RFL Rulebook, Season 31 Edition**.

Each fact is tagged:

| Tag | Meaning |
|---|---|
| **[code]** | Enforced by our plugin or server today. Authoritative. |
| **[decided]** | A rule the league owner stated. |
| **[rulebook]** | From the RFL Rulebook, Season 31 Edition. |
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
- **[decided]** The field is a **completely open floor**: no furniture and no obstacles inside it, with walls all the way round directly outside the 16 × 40. Every field tile is walkable, the out-of-bounds columns are the outermost tiles a player can reach, and paths follow the open-grid behaviour in §2 exactly.
- **[rulebook]** The stadium is a closed POH **2 rooms wide by 5 rooms long** (8 × 8 tiles per room, so 16 × 40), clear of all plants and objects. Out of bounds is the tile along the wall, and the endzone is the last tile row. There are no doors or fireplaces in the endzones. The 2 rooms nearest each endzone have windows on at least one side; field positions are called by **window**.
- **[rulebook]** Sides: the coin-toss loser picks which end to defend in the first half, and the ends swap at half time.
- **[gap]** Where the field sits in the POH template coordinates. This only matters to the plugin, not the simulator.
- **[decided]** A run that passes through an out-of-bounds tile and ends in a corner within the same tick is a **touchdown**. Example: (2, 3) → (1, 2) → (1, 1).
  - A **clicked** path never does this. Clicked paths go straight first and diagonal last (§2), so a click on a corner from any in-play tile reaches it through an in-play tile, for example (2, 3) → (2, 2) → (1, 1). This was checked against all 532 in-play tiles.
  - The ruling matters for routes that **follow** another player, since following goes diagonal first, and for multi-click sequences.

### The ball

- **[code]** The ball is a **handegg**. Any of three items count, all from Easter 2018:
  - Holy (`EASTER18_HANDEGG_LIGHT`)
  - Peaceful (`EASTER18_HANDEGG_BALANCE`)
  - Chaotic (`EASTER18_HANDEGG_CHAOS`)
- **[code]** Item ids are 22355, 22358 and 22361. A player "has the ball" when a handegg is in their **weapon slot**.
- **[code]** A thrown handegg exists as an in-flight projectile, one per type: spotanims `EASTER18_HANDEGG_TRAVEL_SARA`, `_GUTH` and `_ZAM` (1526, 1527 and 1528).
- **[rulebook]** Gnomeballs are also legal, but **only for RB/QB rushing plays**, not passes (their catch animation has a rendering bug).
- **[code] gap:** the plugin only recognises handeggs, so a gnomeball rushing play is invisible to it.
- **[rulebook]** Only one ball may be visible during a play.
- **[gap]** Throw range and flight time per distance. Both are measurable from the projectile (§6).

### Teams

- **[decided] [code]** Each hosted game has:
  - Team A (default name Red, colour `#D9363E`), with a host-set name and colour
  - Team B (default name Blue, colour `#2F6FDE`), with a host-set name and colour
  - **Referees**, fixed name, drawn black and white striped
  - unassigned players
- **[decided]** Every league player must run the plugin.
- **[rulebook]** **5v5 by default**, 4 to 6 per side. 4v4 only if a team has no more than 4 players; 6v6 only if both owners agree. A team must have at least 4 to play.
- **[rulebook]** At least 2 referees, at least one recording. Referees wear yellow. A pass thrown to a referee or a fan is incomplete.
- **[rulebook]** A player who leaves the field and isn't back before the hike plays that down a man short.

### Collisions

- **[decided] [code]** A collision only matters when **at least one of the two players holds a handegg**. Touches with no ball involved are ignored.
- **[code]** A collision is detected from the **3D models touching**, not from tiles (§3).
- **[code]** Walking through someone counts as **one** collision. It starts on the first triangle touch and ends when the two models' bounding boxes separate.
- **[rulebook]** A **tackle** is a defender touching any part of the **body** of an offensive player who has the ball. Touching the ball is not a tackle, and headgear touching (a unicorn horn, say) is neither a tackle nor an incomplete. The plugin's default bare-body model matches this.
- **[rulebook]** A play is dead when a majority of referees call **D** (down) or **Inc**.

### Interceptions

- **[decided] [code]** Checked on exactly **one tick per throw**: the tick the in-flight handegg stops being drawn. On that tick, the interceptor is the player who meets all three conditions:
  - now has a handegg equipped
  - did **not** hold one before the throw
  - is colliding with another player on that same tick (models touching)
- **[code]** Hand-to-hand passes without a throw, and uncontested catches, are **not** interceptions.
- **[code]** Detected locally and shown in chat and as a tile highlight. Interceptions are not sent to the server yet.
- **Conflict with the rulebook.** The rulebook defines these differently:
  - **Incomplete:** a defender touches, or stands on, the intended receiver as the receiver catches the ball.
  - **Interception:** a defender catches a pass, **or** the same player is ruled incomplete **twice in a row** on one drive. If several players are ruled incomplete on one play, all of them are on track for an interception on the next play.
  - What the plugin calls an interception (a catch made while the catcher's model is touching someone) is the rulebook's **incomplete** when an offensive receiver is the one touched. A defender's clean catch, which the plugin ignores, is the rulebook's interception.
- **[rulebook]** A **catch** happens the moment the receiver has both hands on the ball and the ball can no longer be seen in the air. Once the arms start moving down, an incomplete can no longer happen. The plugin's check on "the tick the projectile stops being drawn" lines up with this.

### Fair play

- **[decided]** Banned plugins: **Block Tracker** and **True Tile Player Indicators**. rfl.gg reads the banned list from a config JSON. Unofficial plugins are allowed and shown in beige.
- **[code]** Each report includes the player's own plugin list (enabled and disabled) and logs toggles.

### Game structure

- **[rulebook]** Two **10-minute halves** (1000 ticks each), with a 5-minute half time and a two-minute warning in each half. Three one-minute timeouts per half.
- **[rulebook]** The clock stops on a change of possession, an incomplete pass, out of bounds, a touchdown, a challenge and the two-minute warning. Extra points are not timed.
- **[rulebook]** **4 downs to score.** There are no first downs: a drive scores within four plays or turns over on downs.
- **[rulebook]** A drive starts with the ball at the **second window from the offense's own goal line**.
- **[rulebook]** Overtime: one possession each, repeating in the order 1, 2, 2, 1, 1, 2 and so on until one team leads after equal possessions. From the third set of possessions, every touchdown must go for two.

### A play

- **[rulebook]** **Play clock:** 20 s (about 33 ticks). Hike can't be called before it starts, and the snap must happen within it.
- **[rulebook]** **The snap:** the center starts with the ball. The QB says "Hike" in public chat while standing still, and the center throws the ball to the QB. Neither moves until the QB has the ball. After that the center may run a route.
- **[rulebook]** **Direct snap:** the center may snap to any player on or behind the line of scrimmage instead. This ends the sack clock.
- **[rulebook]** **Sack clock:** 15 s (25 ticks) from the hike. Until it expires, or until the QB passes, hands off, or runs behind the line, a defender running through the QB is an illegal sack. Moving with the ball also opens the QB to a sack. A sack is avoided the moment the throw animation starts.
- **[rulebook]** A player carrying the ball past the line of scrimmage must have it **equipped**. Running past the line without the ball before the throw is a dead-ball penalty.
- **[rulebook]** **Laterals:** a player who has caught or run with the ball may stop, unequip it, and throw to anyone **behind them or level with them**, measured toward the scoring endzone.
- **[rulebook]** **Handoff:** the RB carries their own ball in their inventory. Once the QB unequips, the RB runs through the QB and equips on the QB's tile or within one tile of it, before crossing the line of scrimmage. Only one RB may run through the QB per play, and only once. A handoff ends the sack clock.
- **[rulebook]** A QB throw that goes **backwards** is a pitch (counts as a rushing TD). A throw **level** with the QB is a screen (counts as a pass).

### Scoring

- **[rulebook]** Reaching the end of the field is a **touchdown**. The carrier must enter the endzone **untouched** by the defense.
- **[rulebook]** It's also a touchdown if the carrier's foot and ball fully cross the plane, facing forward, before the tackle lands. Because this is judged on the models, the trailing render matters here (§2).
- **[rulebook]** **Running** into an endzone corner is a touchdown, but a **pass** to the corner is incomplete. A catch on the half-tile marker between the corner and in-bounds is incomplete.
- **[rulebook]** After a touchdown: a 1-point try from the second window from the goal line, or a 2-point try from the fourth window. Stopping it counts as an "XP stop".

### Defense

- **[rulebook]** Following or trading the ball carrier is a penalty (5 yards, replay down). Following a receiver who is running their route is also prohibited. Following or trading an offensive player **through** the QB is not an illegal sack.
- **[rulebook]** **Off-ball** players may accept trades or teleother spells. That counts as blocking.
- **[rulebook]** A defender **equipping a ball** is a penalty.
- **[rulebook]** In 6v6, at most 5 defenders may be in the endzone at once, unless the play starts on the 1-yard line.

### Turnovers

- **[rulebook]** **Interception:** see the conflict note above. If the second incomplete in a row falls on 4th down, the team taking over starts from the original line of scrimmage.
- **[rulebook]** **Fumble:** the center snaps the ball to a defender, **or** one player has two sacks or tackles for loss, in any combination, on back-to-back plays.
- **[rulebook]** **Turnover on downs:** no score in 4 plays.

### Penalties that affect movement

- **[rulebook]** **Emotes and speed boosts** are penalties for both sides. So run speed is the base 1 or 2 tiles per tick.
- **[rulebook]** The usual penalty is **5 yards**, with the down replayed. Unsportsmanlike conduct is 15 yards.
- **[rulebook]** Equipment: nothing in the weapon or shield slot (books are allowed), no capes (the shoulder parrot is allowed), and nothing that hides or extends the feet.

### Still not defined

- **[gap]** **Yards and windows to tiles.** Where are the 1-yard line and the 2nd and 4th windows in tile rows, and how many tiles is 5 yards? Every spot, penalty and conversion depends on this.
- **[gap]** **Where the next down starts.** At the tackle spot (the carrier's tile, or the contact tile)? On the same row and column, or re-centred? Where does the ball go after out of bounds, an incomplete, a sack or a turnover?
- **[gap]** **Where the line of scrimmage is, and what offsides means**, in tiles.
- **[gap]** **Throws:** aimed at a player (the OSRS handegg "toss") or at a tile? Does a ball whose target moves away still get caught, and is that incomplete?
- **[gap]** **Run energy:** speed boosts are banned, but does that include stamina potions? Do players run out of energy during a 10-minute half?
- **[gap]** **Same-tick ordering:** if the carrier enters the endzone on the tick they are touched, which counts? The rulebook's "untouched" and "crosses the plane before the tackle" read as touchdown only if the touchdown happens strictly first.
- **[gap]** **The interception conflict above:** which definition should the plugin follow?

---

## 2. How RuneScape movement works

### The server

- **[osrs-high]** The server runs in **ticks of 600 ms**. All movement is resolved on ticks. Between ticks nothing moves on the server.
- **[osrs-high]** **Walking** moves 1 tile per tick. **Running** moves 2 tiles per tick.
- **[osrs-high]** If a running player has 1 tile left, they move 1 tile that tick.
- **[osrs-high]** Players move in all 8 directions. A diagonal step is blocked if either adjacent cardinal tile is blocked, so there's no cutting corners past walls or furniture.
- **[osrs-high]** **Players do not block players.** Any number of players can stand on the same tile. The only physical contact in RFL is the model overlap our plugin measures; the game itself has none.
- **[osrs-high]** Clicking a tile makes the server path to it. If the tile is unreachable, the player goes to the nearest reachable tile.
- **[osrs-high]** Clicked-tile pathfinding is a breadth-first search **from the player**, in a bounded area around them. Neighbours are tried in the order W, E, S, N, SW, SE, NW, NE, and each tile keeps the first predecessor that reaches it. The path is then traced back from the destination and reduced to turn points ("checkpoints"), at most 25 of them. Source: the rsmod `PathFinder.findPath1` port and the OSRS Wiki Pathfinding page.
  - **Consequence: clicked paths go straight first, diagonal last.** Cardinal neighbours are queued first, so the straight leg wins ties. From (2, 3) to (1, 1) the path is S to (2, 2), then SW. From (0, 0) to (1, 3) it is N, N, then NE.
- **[osrs-high]** Between checkpoints, and when **following** a player or NPC, movement is the naive "follow mode" instead: diagonal toward the target until one axis lines up, then straight. **Follow paths go diagonal first.**
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

> **Uploads paused 2026-10-02.** The plugin is now local-only: it sends nothing and saves every handegg collision and interception to `RUNELITE_DIR/rfl/collisions/YYYY-MM-DD.jsonl`. The server code below is kept on `yume-api` `dev` but is unused.

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
