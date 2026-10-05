# RFL game model: rules, movement and collision

Reference for modelling RuneScape Football League (RFL) plays. Written 2026-10-02 from the
`RFL_plugin` code (master; local-only since 2026-10-02), the design decisions made
while building them, and the **RFL Rulebook, Season 31 Edition**.

Each fact is tagged:

| Tag | Meaning |
|---|---|
| **[code]** | Enforced by the plugin today. Authoritative. |
| **[decided]** | A rule the league owner stated. |
| **[rulebook]** | From the RFL Rulebook, Season 31 Edition. |
| **[measured]** | Measured from our own replay recordings (§7). |
| **[osrs-high]** | Well-established OSRS mechanic. |
| **[osrs-verify]** | My best understanding of OSRS. Measure it before you rely on it (see §6). |
| **[gap]** | Not defined anywhere yet. Needs the league owner. |

---

## 1. League rules we have

### The pitch

- **[code]** Games are played inside a **player-owned house (POH)**, which is an instance.
  - Detected by the POH template regions 7257, 7534, 7535, 7790, 7791, 8046, 8047, 8302 and 8303 (RuneLite's Roof Removal list).
  - Positions use `WorldPoint.fromLocalInstance`, so every player in the same house gets the same template coordinates.
  - **[code]** Collision/incomplete log lines carry this same template `x`/`y`/`plane`, which repeats across the 52 scene chunks that share one template chunk in a POH; `sx`/`sy` is the scene tile (0–103, unique within the loaded house) of the same point, and is what disambiguates which tile (or end zone) an event happened on.
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
- **[decided]** A throw is **aimed at a player**: in OSRS you *Use* the handegg from your inventory on another player. A receiver with a free right hand catches it, and it lands in their weapon slot (OSRS Wiki, Holy handegg).
- **[measured]** Throw timeline, from all 24 throws recorded on 2026-10-02 (`~/.runelite/rfl/replays/*_w354.rflr.gz`). T is the server tick the throw happens on.
  - **Tick T:** the thrower plays anim **7996** and the receiver plays **782** at the same moment. The thrower stood still for the whole animation in every throw. Five throws had the thrower walking until about a tick before T, which fits "walk into range, then throw".
  - **Tick T+1 or T+2:** the receiver has the egg in their weapon slot on the server. It was T+1 in 13 throws and T+2 in 11, and **distance made no difference**. PID order is the likely cause, but that isn't verified.
  - **The visual ball** appears about 41 client cycles after T. It flies **5·d + 6 cycles**, where d is the Chebyshev distance in tiles (d=2: 16, d=3: 21, d=4: 26, d=5: 31, d=8: 46, d=9: 51, d=10: 56). It lands between T+1 and T+3.
  - **Consequence:** the flight is cosmetic. Who gets the ball is fixed at tick T by who was targeted, and the server hands it over before a long throw visibly lands. A defender can't step into the path and take a pass. In OSRS terms, an interception means the thrower targeted a defender.
- **[osrs-verify]** **Max range is 10 tiles** (Chebyshev). No recorded throw went past d = 10, and the one third-party source agrees, but no throw has been tried from 11 or more tiles away.

### Teams

- **[code]** The plugin's local teams: **Team A** (red, `#E6464C`), **Team B** (blue, `#508CF5`) and unassigned (the default). There are no referee or host-set team names in the plugin; those belonged to the retired hosted-game feature of the website.
- **[decided]** Every league player must run the plugin.
- **[rulebook]** **5v5 by default**, 4 to 6 per side. 4v4 only if a team has no more than 4 players; 6v6 only if both owners agree. A team must have at least 4 to play.
- **[rulebook]** At least 2 referees, at least one recording. Referees wear yellow. A pass thrown to a referee or a fan is incomplete.
- **[rulebook]** A player who leaves the field and isn't back before the hike plays that down a man short.

### Collisions

- **[decided] [code]** A collision only matters when **at least one of the two players holds a handegg**. Touches with no ball involved are ignored.
- **[decided] [code]** **Only opposite teams count.** The plugin's local team assignment puts each player on Team A, Team B or unassigned (the default). Collisions, incompletes and the touching-triangle highlight count only between a Team A player and a Team B player. Same-team pairs never count, and **unassigned never counts**, so with nobody assigned nothing is detected. Assignments are made in the panel's Teams view or by right-clicking a player in the house, persist across restarts, and change only when the user changes them or clicks Clear teams. Replays record each player's team (`team` lines).
- **[code]** A collision is detected from the **3D models touching**, not from tiles (§3).
- **[code]** Walking through someone counts as **one** collision. It starts on the first triangle touch and ends when the two models' bounding boxes separate.
- **[rulebook]** A **tackle** is a defender touching any part of the **body** of an offensive player who has the ball. Touching the ball is not a tackle, and headgear touching (a unicorn horn, say) is neither a tackle nor an incomplete. The plugin's default hitbox is the drawn model, worn items included; the experimental **Bare body** hitbox source matches this rule more closely.
- **[rulebook]** A play is dead when a majority of referees call **D** (down) or **Inc**.

### Incompletes

- **[decided] [code]** Owner rulings 2026-10-03, replacing the old once-per-tick check:
  - **Decided at projectile despawn.** The catch is the client cycle the thrown handegg's projectile stops being drawn, checked frame by frame, not at the next game tick (which could be up to 29 cycles later).
  - **Receiver:** the player whose weapon slot gained a handegg for this throw. The server hands the egg over before the cosmetic flight ends, often before the projectile starts, so a gain up to 60 cycles before the throw was first drawn counts, and a weapon packet up to one tick after the catch is still matched.
  - **Contact:** at the catch cycle the receiver is in an **open collision** with an opposing player (it holds while the models' bounds overlap), **or** their models touched within **3 client cycles (60 ms) before** the catch, inclusive.
  - **Contact that begins after the catch does not count**, even if the weapon packet arrives later.
  - **Opposite teams only** (Team A vs Team B), the same gate as collisions.
  - Recorded with the exact catch time: the day file's `timeMs` and `catchCyc`, and the replay `ev` line's `cyc` = `catchCyc` = the catch cycle. `tick` stays for older readers.
- **[code]** Hand-to-hand passes without a throw, and uncontested catches, are **not** incompletes.
- **[code]** Detected locally and, per the settings, shown in chat and as a tile highlight and saved to the local day file. Nothing is sent anywhere.
- **[decided]** Owner ruling 2026-10-03: a catch while in contact is an incomplete pass; the server picks the receiver at the throw, so the flight is cosmetic.
- This resolves what used to be a conflict with the rulebook's own terms:
  - **Incomplete:** a defender touches, or stands on, the intended receiver as the receiver catches the ball.
  - **Interception:** a defender catches a pass, **or** the same player is ruled incomplete **twice in a row** on one drive. If several players are ruled incomplete on one play, all of them are on track for an interception on the next play.
  - What the plugin detects (a catch made while the catcher's model is touching someone) is the rulebook's **incomplete**, per the ruling above — not an interception. The plugin still has no way to detect a true rulebook interception (a defender becoming the server-picked target), since that isn't visible as a catch-while-in-contact.
- **[rulebook]** A **catch** happens the moment the receiver has both hands on the ball and the ball can no longer be seen in the air. Once the arms start moving down, an incomplete can no longer happen. The plugin's check at the cycle the projectile stops being drawn lines up with this.

### Fair play

- **[decided]** Banned plugins: **Block Tracker** and **True Tile Player Indicators**. rfl.gg reads the banned list from a config JSON. Unofficial plugins are allowed and shown in beige.
- **[code]** The plugin saves the local user's own plugin list (enabled and disabled) to a local day file on entering and leaving a house, plus a line for each plugin turned on or off while logged in. Nothing is sent; a player pastes it to the refs with **Copy plugin history**.

### Game structure

- **[rulebook]** Two **10-minute halves** (1000 ticks each), with a 5-minute half time and a two-minute warning in each half. Three one-minute timeouts per half.
- **[rulebook]** The clock stops on a change of possession, an incomplete pass, out of bounds, a touchdown, a challenge and the two-minute warning. Extra points are not timed.
- **[rulebook]** **4 downs to score.** There are no first downs: a drive scores within four plays or turns over on downs.
- **[rulebook]** A drive starts with the ball at the **second window from the offense's own goal line**. That's row **6** when driving from the row-1 end, or row **35** from the row-40 end (see Spots below).
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

- **[rulebook]** **Interception:** see Incompletes above. If the second incomplete in a row falls on 4th down, the team taking over starts from the original line of scrimmage.
- **[rulebook]** **Fumble:** the center snaps the ball to a defender, **or** one player has two sacks or tackles for loss, in any combination, on back-to-back plays.
- **[rulebook]** **Turnover on downs:** no score in 4 plays.

### Penalties that affect movement

- **[rulebook]** **Emotes and speed boosts** are penalties for both sides. So run speed is the base 1 or 2 tiles per tick.
- **[rulebook]** The usual penalty is **5 yards**, with the down replayed. Unsportsmanlike conduct is 15 yards.
- **[rulebook]** Equipment: nothing in the weapon or shield slot (books are allowed), no capes (the shoulder parrot is allowed), and nothing that hides or extends the feet.

### Spots, in tiles

- **[decided]** **One yard is one tile**, counted from the endzone. The 1-yard line is row **2** (or **39**), and 5 yards is 5 tiles.
- **[decided]** **Windows** are on the 3rd and 6th tile of each 8-tile room wall, so the window rows are **3, 6, 11, 14, 19, 22, 27, 30, 35, 38**. Counted from the row-1 end, the 2nd window is row 6 and the 4th is row 14. From the row-40 end they are 35 and 27.
  - Drive start and 1-point try: row **6** / **35**.
  - 2-point try: row **14** / **27**.
- **[decided]** **Next down:** on the row where the carrier was tackled, **re-centred** across the width.
- **[decided]** **Line of scrimmage:** the row the ball is snapped on. **Offsides** is either team crossing that row before the snap.
- **[decided]** If the carrier enters the endzone on the **same tick** they are touched, it's a **down at the 1-yard line** (row 2 / 39), not a touchdown.
- **[decided]** **Stamina potions do nothing** under the rules. The sim treats players as always able to run.
- **[decided]** Re-centring puts the ball on column **8 or 9**, either one; the owner doesn't care which. The sim uses 8.
- **[decided]** The tackle row is where the **models intersect** (the contact tile from the collision detector), not the carrier's server tile.
- **[decided]** After any dead ball (out of bounds, incomplete, sack, turnover), players line up on their sides of the line of scrimmage and the ball is hiked back to the QB from there. The rulebook's spots apply. Sim default until the owner says otherwise: out of bounds = the exit row, incomplete = the previous line of scrimmage, sack = the contact row, turnover = the dead-ball row with the direction flipped.

### Still not defined

- **[osrs-verify]** Does a catch interrupt the receiver's movement? The wiki says catching interrupts the receiver's current action. In the recordings, 4 of the 24 receivers were moving within half a second of the landing, but every receiver was standing still at the throw, so the recordings can't confirm or rule it out.

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
- **[osrs-high]** Each client sees every other player with **its own timing**, depending on its latency and when packets arrive. Two clients can disagree for a moment about whether two models touched, so one client's recording is one view of a play, not ground truth.
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

- **[code]** **What's tested:** each player's **posed 3D model** for the current animation frame: the drawn model (worn items included) by default, or, with the experimental **Bare body** hitbox source, the player's identity-kit body parts without equipment, so gear can't bulk up a hitbox.
  - The model is placed at the rendered position (§2), rotated by the actor's orientation, with height measured from the soles.
- **[code]** **Which pairs:** only a Team A player against a Team B player, and only while one of them holds a handegg (or the pair is already in an open collision, or one of them is the likely receiver of a handegg in flight). No other pair is ever triangle-checked.
- **[decided] [code]** **Whose models are built:** every Team A and Team B player's, every frame, whether or not anyone holds the handegg (owner ruling 2026-10-03). The incomplete rule needs the receiver's contacts during the flight and in the 3-cycle grace window before the catch, before anyone is known to hold the egg. Unassigned players' models are not built, since they can never count; Show hitboxes builds everyone's so it can draw them.
- **[code]** **Test:** exact triangle-triangle intersection (Möller 1997) between two models.
  - Broad phase: whole-model bounding boxes, then per-triangle boxes with a sort-and-sweep pass.
  - Fully transparent and hidden faces are skipped.
- **[code]** **Contact:** starts when any triangles touch, and holds until the two models' bounding boxes separate.
- **[code]** **Overlap ("depth"):** the number of touching triangle pairs, capped at 512. It's unitless: higher means more overlap, not a distance.
- **[code]** **Where:** the tile under the centroid of the touching triangles.
- **[code]** **Cost:** mesh checks run on frames, with the overlap sampled once per tick. There is no debug panel or Debug logging setting any more: each replay writes one performance summary line to `client.log` when it closes (`RFL replay closed …`, average and worst ClientTick cost).
- Consequence for modelling: a collision depends on the **rendered** models, which trail the true tiles. Two players can collide in the models while their true tiles are a tile apart, or pass on adjacent tiles without their models touching, depending on stride, turning and timing.

---

## 4. Nothing is sent anywhere

- **[code]** The plugin is local-only. Collisions and incompletes go to `RUNELITE_DIR/rfl/collisions/YYYY-MM-DD.jsonl` (with Save collisions on), the local user's own plugin list to `rfl/plugins/`, and replays to `rfl/replays/`. It has no network code at all.
- The server-side reporting, pairing and hosted-game features described in earlier versions of this file were retired on 2026-10-02 and removed from the plugin.

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
| Overlap cap | 512 triangle pairs | code |

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

A movement trace logger can be added on request. The Debug logging setting was removed (2026-10-03), so it would get its own setting, off by default like every other switch.

---

## 7. Measured from a real recording (2026-10-02)

Source: `2026-10-02_192236_w354.rflr.gz`. 5 min 16 s in a POH on world 354, game revision 241, 3 players, 8 throws, 34 collisions, 2 incompletes. These are **[measured]** values. They replace the matching [osrs-verify] guesses in §2 and §5.

| What | Measured | Notes |
|---|---|---|
| Client cycles per game tick | median **30** (28–32, one 18 during lag) | So a client cycle is ~20 ms and a tick is ~600 ms, with jitter. |
| Walk speed (rendered) | **~4 local units/cycle** straight, ~6 diagonal | 128 units per tile ÷ 30 cycles = 4.3. |
| Run speed (rendered) | **~8 units/cycle** straight, ~10–11 diagonal | |
| Catch-up speed | bursts of **13–16 units/cycle** | The client speeds up when steps queue. This is where the model "jumps" toward its true tile. |
| Turn rate | **32 orientation units/cycle** almost always | A full turn takes 64 cycles (1.28 s), a 180° turn 0.64 s. Sharp direction changes leave the model facing the old way for up to ~0.6 s. |
| Pose animations | 808 idle, 819 walk, 824 run, 820–823 turn/sidestep variants | Counts in the recording: run 4313, walk 2655, idle 1977. |
| Action animations | 7996 throw, 782 catch, 2111 pick up (likely) | 7996 runs on the thrower just before the projectile; 782 on the receiver at the catch. |
| Projectile pre-roll | appears **~0.8 s before its start cycle**, parked at (0,0) | Fixed in the recorder (plugin `0da3874`): ball rows begin at the start cycle. |
| Throw flight time | 4.2–4.8 tiles: **26–31 cycles (0.52–0.62 s)**; 8.8 tiles: 51 (1.02 s); 9.7 tiles: 56 (1.12 s) | About **5.4 cycles (0.11 s) per tile + ~3 cycles**. |
| Throw arc | starts ~8 units above the catch height, peaks **45–115 units** higher (longer throws arc higher) | z is negative-up. |
| Collision duration | median **360 ms**, 19 ms to 14.4 s | Long ones are players standing together. |
| Collision overlap | median **144** touching triangle pairs, often hitting the 512 cap | |
| Player tracking range | at least **22 tiles** from the recorder, with 2 despawns (both players leaving) | Wider than the ~15 tiles assumed. A full-size game is still needed to confirm one recorder covers the pitch. |

Still not measured:
- **Rendered position vs true tile:** recordings now carry each player's true tile per tick (`tt` lines), but no recording has been analysed for it yet.
- **PID order effects.**
- **Long throws past 10 tiles.**
