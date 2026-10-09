# RFL Audit

A RuneLite plugin for refereeing RuneScape Football League (RFL) games. RFL is handegg — American
football, played with a thrown item called a handegg — inside a player-owned house, with refs
watching for who touched whom and whether a catch counts. RFL Audit watches the same plays your
refs do, and keeps a record of what it saw, entirely on your own computer.

![A refereed RFL play, with touching triangles highlighted](docs/media/hero.png)
<!-- Capture: a game in progress inside the POH, two opposing-team players mid-collision with the
handegg out, Show touching triangles on so the overlap is visible. Click: nothing, just get the
moment. Crop: full client window (panel plus game view). Size: ~1280px wide. -->

## What it does

- **Collisions** — detects when two players' 3D models touch while at least one holds the ball (a
  handegg, or a gnomeball carried on a rushing play), but only between a player on Team A and a player on Team B. Same-team pairs and unassigned
  players never count.
- **Incompletes** — a catch made while the receiver is in contact with an opposing player. Decided
  at the moment the thrown handegg's projectile stops being drawn, not on the next game tick.
- **Teams** — a local Team A / Team B / unassigned label per player, set from the panel or by
  right-clicking them in the house. Only Team A against Team B is ever detected.
- **A plugin log for refs** — your own installed plugin list, saved on entering and leaving a
  house, plus every plugin you turn on or off while logged in. You decide if and when to share it.
- **Replay recording** — records a house visit to a local file for the RFL replay viewer:
  players, the house, the ball, every collision and incomplete, and optionally overhead chat.
- **Nothing is sent anywhere.** The plugin has no network code at all. Every log and every replay
  stays on your computer until you choose to share it.

## Quick start

1. Install **RFL Audit** from the Plugin Hub.
2. Open the RFL panel in the sidebar (the whistle icon).
3. Turn on the switches you want in the plugin's settings — **everything defaults to off**, so
   nothing is detected or recorded until you say so. At minimum, turn on **Detect contacts** to
   see collisions, and **Detect incompletes** if you also want those ruled.
4. Assign players to **Team A** and **Team B**, either in the panel's Teams view or by
   right-clicking them in the house. Nothing is detected until both teams have at least one player.
5. Play. Collisions and incompletes show up in the panel as they happen.

## The panel

Open with the whistle icon in the sidebar (shown only when **Show RFL panel** is on). Top to
bottom: a status line (in a house or not), the replay card, the Collisions / Incompletes / Teams
cards, the latest event, the selected list, and a footer with Copy plugin history, the record
button, Save last 3 min (while Record replays is on) and Open folder.

### The recording card

The replay card always holds its place, so starting or finishing a recording never moves anything
else in the panel. It has four states:

- **Recording** — elapsed time, file size, and how many distinct player models have been seen.
- **Saving** — a progress bar while the gzip file is finished on its own thread.
- **Saved** — the file name and final size, with an Open folder shortcut.
- **Error** — "Couldn't save replay" and a short reason (for example, a full disk).

![The replay card moving through idle, recording, saving and saved](docs/media/panel-recording-states.gif)
<!-- Capture: Record replays on, panel open. Click: enter a house (starts recording), wait a few
seconds, leave the house (saves, then shows saved). Length: 5-6 s, sped up if needed to show all
four states. Crop: sidebar only, just the replay card. Size: ~360px wide. -->

### The Collisions, Incompletes and Teams cards

Click **Collisions** or **Incompletes** to show that session's list below the cards; click
**Teams** for the Teams view instead. The clicked card stays highlighted to show which list is open.

![Clicking the Collisions, Incompletes and Teams cards switches the list below them](docs/media/panel-view-switch.gif)
<!-- Capture: panel open with at least one collision and one incomplete already logged. Click:
Collisions, then Incompletes, then Teams. Length: 3-4 s. Crop: sidebar only (cards plus the list
area). Size: ~360px wide. -->

### Clicking a row

Click a row in the Collisions or Incompletes list to highlight the tile it happened on in the game
view (with Highlight contacts / Highlight incompletes on); click it again to clear the highlight.

![Clicking a collision row highlights its tile in the game view](docs/media/panel-row-highlight.gif)
<!-- Capture: panel open on the Collisions (or Incompletes) view with at least one row, the
matching Highlight setting on. Click: a row. Length: 2-3 s. Crop: full client window, since the
highlight is in the game view while the click is in the sidebar. Size: ~700px wide. -->

### The Clear buttons

Each list (and the Teams view) has a Clear button behind an inline confirmation. Clearing only
resets what the panel shows this session — **the saved files in `rfl/collisions` are kept.**

![The inline confirmation before Clear removes anything](docs/media/panel-clear-confirm.png)
<!-- Capture: a view with at least one row or one team assignment. Click: Clear once (not the
confirm), so the inline question is showing. Crop: sidebar only, the Clear row and the question
beneath it. Size: ~360px wide. -->

### Copy plugin history

Copies today's plugin log to the clipboard, laid out for Discord: each house visit's plugin list
and every plugin turned on (`+Name`) or off (`-Name`) during it, wrapped in a code block. Discord's
2000-character limit is respected — older visits are dropped and counted as "… N more visits" if
the whole day's history wouldn't fit, and the newest visit is always kept whole.

![Copy plugin history and its result line](docs/media/panel-copy-history.gif)
<!-- Capture: at least one house visit logged today. Click: "Copy plugin history". Length: 2-3 s.
Crop: sidebar only, the footer (button plus result line). Size: ~360px wide. -->

### The Record button

Toggles the **Record replays** setting. Its label says what a click does, not what's happening now:
"Start recording" when off, "Stop recording" (in red) when on. Recording itself only starts once
you're inside a house.

![The record button's two labels](docs/media/panel-record-button.png)
<!-- Capture: two shots (or one composite), Record replays off then on. Crop: sidebar only, the
record button row. Size: ~360px wide. -->

### Save last 3 min

While a replay is recording, **Save last 3 min** saves the last few minutes of it as a separate,
self-contained replay (a *replay buffer clip*), so you can record a whole game and still pull out
just the last play for a quick booth review. The full recording keeps going, untouched. The label
follows the **Clip length** setting ("Save last 3 min" at 180 seconds, "Save last 1:30" at 90).
The **Save replay clip hotkey** does the same from anywhere in the client.

The button shows while **Record replays** is on and works once a file is open (inside a house). The
line under it says how the save is going:

- "Saving clip... 42%" while the clip is cut and written on its own thread.
- "Saved clip `<file>` (845 KB, 3:00)" for a few seconds, with an Open folder button for
  `rfl/replays/snapshot reviews`. A clip that came out shorter says why: "cut short to fit memory"
  (see [Replay buffer clips](#replay-buffer-clips)) or "all recorded so far".
- "Couldn't save clip: `<reason>`" if it failed (for example, a full disk).
- Pressing the hotkey while nothing is recording shows a short notice instead.

![Saving a replay buffer clip from the panel](docs/media/panel-save-clip.gif)
<!-- Capture: Record replays on, recording in a house for at least 30 s, panel open. Click: "Save
last 3 min", wait for "Saved clip ...", then click its Open folder. Length: 4-6 s. Crop: sidebar
only, the footer (record button, Save last, result line). Size: ~360px wide. -->

## Teams

Every league player should run the plugin and be assigned to a team before a game. Assign a
player from the panel's **Teams** view (A / B / - buttons per row) or by right-clicking them while
in the house, which adds **RFL: Team A**, **RFL: Team B** and **RFL: Unassign** entries (only the
ones that would change something) to their player menu. The right-click entries need **Teams →
Right-click team options** turned on (off by default); the panel's Teams view always works.

![Assigning teams from the right-click menu](docs/media/teams-right-click.gif)
<!-- Capture: in a house, another player nearby, nobody assigned yet. Click: right-click the
player, hover Player options, click "RFL: Team A". Length: 2-4 s. Crop: game view only, menu
included. Size: ~600px wide. -->

![Assigning a team from the panel's Teams view](docs/media/panel-team-assign.gif)
<!-- Capture: panel open on the Teams view, at least one player present. Click: the A button, then
B, then -, on the same row. Length: 3-4 s. Crop: sidebar only, the Teams view. Size: ~360px wide. -->

Assignments **persist across restarts** and change only when you change them or click **Clear
teams** — there's no per-game reset. While the panel's Teams view is open on screen, each
assigned player's tile is tinted their team colour in the game view, display-only, like core's
Player Indicators.

![Team-coloured tiles under players while the Teams view is open](docs/media/team-tiles-overlay.gif)
<!-- Capture: at least two players on different teams, Teams view open in the panel. Click: none;
just show the tiles. Length: 2-3 s. Crop: game view, panel edge included to show the Teams view is
open. Size: ~700px wide. -->

**Only Team A against Team B ever counts.** Same-team pairs and anyone unassigned never trigger a
collision, an incomplete, or the touching-triangle highlight — so with nobody assigned, nothing is
detected.

## Rules and how detection works

RFL Audit models a specific set of RFL rules, in plain language:

- **The pitch** is the player-owned house itself. The plugin doesn't know about yardage, downs or
  field markings — just what's in front of it: players, the handegg, and contact between them.
- **A collision** is two players' actual 3D models touching — the posed, rendered mesh, worn items
  included by default but never the held ball (an experimental **Bare body** hitbox strips
  equipment out). It's measured
  by exact triangle-triangle intersection, not by which tile anyone is standing on, so two players
  can collide while a tile apart on the server, or miss each other on adjacent tiles, depending on
  how their models are moving that frame.
- **The ball gate.** A touch only matters if at least one of the two players holds the ball in
  their weapon slot — any of the three handeggs (Holy, Peaceful, Chaotic) or a gnomeball, which
  some players carry on rushing plays — and contact with no ball involved is ignored. The holder
  is named in the day file, the replay `ev` line and the panel. Incompletes stay handegg-only: a
  gnomeball is carried, never thrown.
- **The ball is not the body.** The held ball's own triangles (handegg or gnomeball) are left out of the holder's
  hitbox, so the ball touching someone is never a collision, a touching-triangle highlight or an
  incomplete's contact. Only bodies (and worn gear) touching count.
- **The incomplete rule.** The catch is decided at the exact client frame the thrown handegg's
  projectile stops being drawn, not at the next game tick. The receiver is in contact if their
  model was touching an opposing player at that frame, or touched one within 3 client cycles
  (about 60 ms) before it. Contact that starts *after* the catch never counts, even if the game's
  own weapon-slot update arrives later.
- **Why every team player is tracked.** The incomplete rule needs to know the receiver's contacts
  during the whole flight, before anyone is known to hold the egg — so the plugin builds a mesh for
  every Team A and Team B player, every frame, holder or not. Unassigned players are skipped; they
  can never pass the team gate.

This is a summary. The full rule set, the OSRS movement model it's built on, and the measurements
behind it are in [`docs/rfl-game-model.md`](docs/rfl-game-model.md).

## Replays

With **Record replays** on, each house visit is recorded to a local file for the RFL replay
viewer. A recording holds:

- every player's position, pose, appearance and true tile
- the models the client draws for them
- the house itself (the pitch floor and the objects in it)
- the handegg, in hand or in flight
- every collision and incomplete, as they're ruled
- public overhead chat, **only** with **Record overhead chat** also on (off by default; a shared
  replay then contains other players' public chat too)
- each player's team

### Where files go

Replays are written to `.runelite/rfl/replays/` inside your RuneLite folder. Replay buffer clips go
in its `snapshot reviews` subfolder (with the space): `.runelite/rfl/replays/snapshot reviews/`,
created the first time a clip is saved. The replay website opens files from any folder.

### File name templates

Two settings name files: **Replay file name** for each recording, and **Clip file name** for each
replay buffer clip. Both are templates expanded by the same rules: `.rflr.gz` is always appended,
characters a file name can't hold become `_`, a name already on disk gets `-2`, `-3`, and so on, and
an empty or invalid template (an unknown token or a stray brace) falls back to that setting's
default. The folder name is never touched by these rules.

| Token | Replay | Clip | Expands to | Example |
|---|---|---|---|---|
| `{date}` | yes | yes | the date the file was started (recording) or saved (clip), `yyyy-MM-dd` | `2026-10-05` |
| `{time}` | yes | yes | the time the same way, `HHmmss` | `210409` |
| `{world}` | yes | yes | the world number | `354` |
| `{player}` | yes | yes | your RSN, as shown in game (`unknown` before it is known) | `Ref Bob` |
| `{source}` | | yes | the source recording's file name without `.rflr.gz` | `2026-10-05_200102_w354` |
| `{length}` | | yes | the clip's length, `m-ss` | `3-00` |
| `{collision}` | | yes | the clip's most recent collision as `<a>-vs-<b>`, the ball carrier first when exactly one of the pair held the ball; spaces in names become `_`; `no-collision` when the clip has none. These are the same team-gated collisions the panel lists. | `Iron_Amy-vs-Big_Zed` |
| `{collisionTime}` | | yes | when that collision started, `HHmmss`; nothing when there is none, and a separator next to it goes too, so no `__` is left behind | `210301` |

Defaults:

| Setting | Default | Example |
|---|---|---|
| Replay file name | `{date}_{time}_w{world}` | `2026-10-03_114112_w354.rflr.gz` (the name every replay had before the setting existed) |
| Clip file name | `{date}_{time}_w{world}_{collision}` | `2026-10-05_210409_w354_Iron_Amy-vs-Big_Zed.rflr.gz` |

More examples:

| Template | Setting | Result |
|---|---|---|
| `{player}_{date}_w{world}` | Replay | `Ref Bob_2026-10-05_w354.rflr.gz` |
| `{source}_clip_{time}` | Clip | `2026-10-05_200102_w354_clip_210409.rflr.gz` |
| `review_{collisionTime}_{collision}_{length}` | Clip | `review_210301_Iron_Amy-vs-Big_Zed_3-00.rflr.gz`, or `review_no-collision_3-00.rflr.gz` with no collision |

### Replay buffer clips

While a file is recording, the plugin keeps its last few minutes in memory, so **Save last 3 min**
(or the hotkey) can write them out as their own replay at any moment. The website opens a clip on
its own, exactly like a full recording. A clip holds, in order:

1. the recording's `hdr` line with `cyc` set to the clip's first cycle (the website starts the
   timeline there) and a `clip` field naming where it came from:
   `"clip":{"source":"2026-10-05_200102_w354.rflr.gz","fromCyc":81234,"toCyc":90234,"seconds":180}`;
2. the `plugins` line as it stood at the clip's start (any toggles before it applied);
3. every `model` line the clip uses, delta bases included, before anything that uses them;
4. the house as it stood at the clip's start, as one complete `pitch` line (no `locs` lines);
5. each player present at the clip's start: their `spawn`, `team`, appearance, position and pose
   (`f`), model (`pm`), spot anims and true tile, all at the clip's first cycle;
6. every recorded line from the clip's window, in its original order (a house reload inside the
   window comes through as its own `pitch` line, as in the recording).

**Memory.** The buffer keeps the clip length plus 10 seconds of lines, capped at 32 MB. Measured
with 20 players all moving and changing pose every frame for 3 minutes 10 seconds: about **9.3 MB**
(7.5 MB of line text plus bookkeeping), so the cap is never reached at the default length. Model
lines are kept for the whole recording, compressed (a delta-encoded player pose is about 5.6 KB),
since a later pose can reuse any earlier one. If the cap is reached anyway, the oldest lines go
first: the clip is just shorter, and the panel says "cut short to fit memory".

**Cost and safety.** Saving costs the client thread one queued step (about 7 microseconds). The
buffer is snapshotted on the replay writer's thread, in order behind every line already recorded,
and the clip is cut and written on a thread of its own. A clip is announced as saved only once its
gzip trailer is on disk; a failed clip's partial file is deleted.

**Clip length** can be changed mid-recording: a longer length keeps everything already buffered and
grows into it, a shorter one drops what is now too old.

### Crash safety

The file is gzip, written on its own thread. The stream is sync-flushed every 2 seconds, so a
client crash loses at most the last 2 seconds — everything written before the last flush still
decompresses. If the disk falls more than 64 MiB behind (a very slow or full disk), the current
file fails cleanly with what was already queued still written, rather than growing memory
unbounded.

### Typical sizes

Measured from real recordings (gzipped `.rflr.gz` on disk):

| Recording | Players | Length | Size |
|---|---|---|---|
| Small house test | 2 | 1 min 55 s | 0.6 MB |
| Full house, off-centre start | 2 | 1 min 3 s | 0.7 MB |
| Full match | about 20 | 13 min 53 s | 7.9 MB |

Roughly a quarter to half a megabyte of each file is the house itself, captured once at the start
(about 770 to 2,300 house objects depending on the house). After that, size grows with how many
distinct players, outfits and animation poses the recording sees; a busy 20-player match comes to
about 0.5 MB per minute.

### Viewing a replay

Open a recorded file at **[rfl.gg/replay](https://rfl.gg/replay)**.

> A dev build of the viewer, for testing against replays made by a dev build of the plugin, is at
> **<https://dev.rfl.gg/replay>**.

![The replay viewer's file list](docs/media/replay-viewer-list.png)
<!-- Capture: open rfl.gg/replay (or dev.rfl.gg/replay) with a replay loaded. Screenshot the
browser window. Crop: browser viewport. Size: full width. -->

![A replay playing back in the viewer](docs/media/replay-viewer-playback.gif)
<!-- Capture: a replay open and playing. Length: 4-6 s. Crop: the pitch/viewer area of the page.
Size: full width. -->

## Logs on disk for refs

Two folders under `.runelite/rfl/`, independent of replays, each one JSON-lines file per local
day (`YYYY-MM-DD.jsonl`):

### `rfl/collisions/`

One line per finished collision and per incomplete, while **Save collisions** is on. Written in
local date of the event's end time.

A collision line:

```json
{"type":"collision","a":"Amy","b":"Bob","ball":["Bob"],"startMs":1696300000000,"endMs":1696300000600,"startTick":120,"endTick":121,"world":354,"x":3222,"y":3421,"plane":0,"sx":42,"sy":17,"maxTriangles":187}
```

An incomplete line:

```json
{"type":"incomplete","receiver":"Amy","contacts":["Bob"],"timeMs":1696300005000,"tick":130,"world":354,"x":3222,"y":3421,"plane":0,"sx":42,"sy":17,"catchCyc":3912}
```

`x`/`y`/`plane` are template coordinates, which repeat across the many scene chunks a
player-owned house reuses; `sx`/`sy` is the scene tile, unique within the loaded house, and is
what actually tells you which tile an event happened on.

### `rfl/plugins/`

Your own plugin list, **always on** — this log is the plugin's main feature and isn't behind a
switch. A snapshot line on entering and leaving a house, and a toggle line whenever you turn one of
your plugins on or off while logged in.

A snapshot line (trimmed to two plugins for brevity):

```json
{"type":"snapshot","timeMs":1696300000000,"rsn":"Amy","world":354,"plugins":[{"name":"RFL Audit","enabled":true,"source":"hub"},{"name":"XP Tracker","enabled":true,"source":"builtin"}],"event":"enter"}
```

A toggle line:

```json
{"type":"toggle","timeMs":1696300100000,"rsn":"Amy","world":354,"name":"Grand Exchange","enabled":false}
```

`source` is `"builtin"` (ships with RuneLite), `"hub"` (installed from the Plugin Hub) or
`"sideloaded"` (anything else). Only your own client's `PluginManager` is read — no other player's
plugins are ever known to the plugin.

Open either folder with the panel's **Open folder** button, which opens `.runelite/rfl/` itself.

## Configuration reference

Every setting defaults to off (or to the value shown); nothing detects, draws or records until you
turn it on.

| Name | Key | Default | What it does |
|---|---|---|---|
| Show RFL panel | `showPanel` | `false` | Adds the RFL panel to the sidebar: house status, the replay recorder, this session's collisions and incompletes, team assignments, Copy plugin history and Open folder. |
| Detect contacts | `reportContacts` | `false` | Inside a player-owned house, detects when any two players in view touch while either holds a handegg or gnomeball. Incompletes need this on. |
| Hitbox source | `hitboxSource` | `EQUIPPED` | Experimental. `EQUIPPED`: the model you see, armour included; a held handegg's or gnomeball's own geometry never counts. `BARE_BODY`: the player's body without equipment. Changes which contacts are detected. |
| Save collisions | `saveCollisions` | `false` | Appends every collision and incomplete to `rfl/collisions`, one file per day. |
| Highlight contacts | `highlightContacts` | `false` | Briefly highlights the tile under each handegg collision as it starts. |
| Contact highlight colour | `contactHighlightColor` | `#FFE600` at ~60% alpha (RGBA 255,230,0,153) | Colour of the contact tile highlight, and of touching triangles. |
| Highlight duration | `highlightDurationMs` | `1200` ms (range 200–5000) | How long a contact or incomplete tile highlight takes to fade out. |
| Show touching triangles | `showTouchingTriangles` | `false` | Fills the exact triangles where two players' models touch, in the contact colour, for handegg pairs on opposite teams. |
| Show hitboxes | `showHitboxes` | `false` | Draws a wireframe of every in-view player's contact mesh while detection runs; touching triangles are filled as above. |
| Hitbox colour | `hitboxColor` | RGBA 255,255,255,57 (white, ~22% alpha) | Colour of the Show hitboxes wireframe. |
| Detect incompletes | `detectIncompletes` | `false` | A catch made while in contact with another player is an incomplete pass. Needs Detect contacts on. |
| Chat message | `incompleteChatMessage` | `false` | Posts a game message naming who caught the handegg in contact and who they were in contact with. |
| Highlight incompletes | `highlightIncompletes` | `false` | Briefly highlights the tile under the player who caught the handegg in contact. |
| Incomplete colour | `incompleteColor` | `#00C8FF` at ~70% alpha (RGBA 0,200,255,180) | Colour of the incomplete tile highlight and chat label. |
| Record replays | `recordReplays` | `false` | Records every player-owned house visit to a local replay file (`rfl/replays`) for the RFL replay viewer. |
| Replay file name | `replayFileName` | `{date}_{time}_w{world}` | Name template for each replay file. See [File name templates](#file-name-templates) for tokens and fallback behaviour. |
| Record overhead chat | `recordOverheadChat` | `false` | Adds public overhead chat (yours too) to replays, for keeping time. A shared replay then contains other players' public chat. |
| Right-click team options | `teamMenuEntries` | `false` | Adds RFL: Team A / Team B / Unassign to other players' right-click menus in a house. The panel's Teams view works either way. |
| Save replay clip hotkey | `saveClipHotkey` | not set | While a replay is recording, saves its last Clip length as a [replay buffer clip](#replay-buffer-clips) in `rfl/replays/snapshot reviews`. Same as the panel's Save last button. |
| Clip length (seconds) | `clipSeconds` | `180` (range 30–300) | How much a saved clip holds, counting back from when you save it. Changes apply to the recording in progress. |
| Clip file name | `clipFileName` | `{date}_{time}_w{world}_{collision}` | Name template for each clip. See [File name templates](#file-name-templates) for tokens and fallback behaviour. |

Team assignments are stored under a separate, hidden key (`teams`) that has no entry in the
settings UI — they're managed entirely from the panel's Teams view and the right-click menu.

## Performance

Detection runs on the client thread every frame, so it's built to stay cheap even with a full game
on screen. From the plugin's own stress benchmark (`./gradlew benchmark`, 1000 frames after 500
warm-up, 1920-triangle meshes per player), total per-frame cost (model build + contact tracking +
replay sampling) at 30 players:

| Overlapping pairs | Mean | p95 | Max |
|---|---|---|---|
| 0% piled up | 1.0 ms | 1.3 ms | 1.8 ms |
| 30% piled up, as in normal play | 1.1 ms | 1.4 ms | 3.5 ms |
| 30% piled up, with Show touching triangles on | 2.0 ms | 3.0 ms | 4.8 ms |

("Piled up" means players standing in jostling groups of 3-4, the worst case for triangle-triangle
checks; one player always holds the handegg, since a touch with no ball involved is never
triangle-checked.) "As in normal play" is the overlap sampled once per tick; **Show touching
triangles** forces a full recount of overlapping triangles every frame instead, which is why it
costs more. Full table: `build/reports/benchmark/stress.md`.

The approach to staying inside the client's frame budget:

- **Mesh checks only run on pairs that can matter** — opposite teams, with at least one holding
  a handegg (or already in an open collision, or the likely receiver of a flying one). No other
  pair is ever triangle-checked.
- **Overlap is sampled once per tick**, not every frame, even though contact itself is detected
  every frame.
- **The house/pitch model capture is spread over ClientTicks** (`FrameBudget`, a 1 ms-per-tick
  budget), one piece at a time, so scanning a house with a few hundred objects never stalls a
  single frame.
- **All file IO is off the client thread.** Day-file writes and replay lines go through a
  `SerialQueue` onto an executor or the replay writer's own daemon thread; the client thread never
  waits on disk.

## Privacy and fairness

What the plugin can see about other players, and why none of it is an unfair advantage:

- **Positions and appearance.** Detection and replays use what's already drawn on your screen —
  other players' rendered models, the same thing your eyes see. Nothing about them is looked up
  that isn't visible.
- **Overhead chat** is recorded only with **Record overhead chat** on (off by default), and only
  the public text already shown above players' heads in the house, for keeping time in the replay.
  The setting's own description warns that a shared replay then contains other players' public
  chat.
- **Plugin lists** — only your own. The plugin reads your own client's `PluginManager` through
  public API; no other player's installed plugins are ever known to it, let alone recorded.
- **Not an advantage.** Collision and incomplete detection rules a player-run minigame from what's
  already drawn on screen — no combat, PvP, skilling or economy effect. An incomplete, at most,
  posts an optional local chat message to the player it ruled on.
- **Show hitboxes** draws the triangles of a model the client is already rendering, the same kind
  of thing core RuneLite overlays (hull, outline) already do — it doesn't reveal anything about a
  player that wasn't already on screen.

## FAQ and troubleshooting

**Nothing is being detected.** Check, in order: are both **Detect contacts** (and **Detect
incompletes**, if you want those) turned on — everything defaults to off; are at least one Team A
and one Team B player assigned — unassigned and same-team pairs never count; is at least one of
the two players holding a handegg or gnomeball.

**The panel is missing from the sidebar.** Turn on **Show RFL panel** in the plugin's settings —
it's off by default.

**A replay won't open in the viewer.** Make sure you're pointing the viewer at a `.rflr.gz` file
from `rfl/replays`, not a collision or plugin log file. If the file was being written when the
client crashed, everything up to the last 2-second flush should still be readable; anything after
that is lost.

**I want to sideload a custom build.** The RuneLite launcher doesn't support sideloading — RFL
Audit is distributed through the Plugin Hub. For local development or testing a change, run it
with `./gradlew runClient` / `gradlew.bat runClient` instead (see below).

## For developers

### Building and testing

```
./gradlew build        # compiles, runs tests and the Hub compliance checks
./gradlew runClient     # launches RuneLite with the plugin loaded, via RflPluginLauncher
./gradlew benchmark     # the per-frame stress benchmark (not part of build); -Pframes, -Pwarmup
```

`runClient` runs `com.rfl.RflPluginLauncher` off the test classpath. `HubRulesTest` and
`HubPackagingTest` (also run by `build`) check the Plugin Hub rules listed in
[`docs/plugin-hub-compliance.md`](docs/plugin-hub-compliance.md) — forbidden APIs, injected Gson,
the listing icon and plugin properties, and so on.

### Package layout

| Package | Holds |
|---|---|
| `com.rfl` | `RflPlugin` (wiring only), `RflConfig`, `ClipHotkey`, `Handegg` |
| `com.rfl.contact` | Collision detection: `ContactDetector`, `ContactTracker`, `Collision`, `BareBody`, `ContactHighlights` |
| `com.rfl.incomplete` | Incomplete detection: `IncompleteDetector`, `IncompleteReporter`, `Incomplete` |
| `com.rfl.log` | Local logs: `CollisionLog`, `PluginLog`, `PluginAudit`, `PluginSnapshotter`, `PluginHistory` |
| `com.rfl.overlay` | Drawing: `ContactHighlightOverlay`, `EventTileOverlay`, `HitboxOverlay`, `TeamTileOverlay`, `RflOverlays` |
| `com.rfl.panel` | The sidebar panel: `RflPanel`, `RflPanelController`, `PanelModel`, `ReplayCard`, `TeamsView`, `EventListView`, `LatestCard`, `SessionEvents` |
| `com.rfl.replay` | Replay recording: `ReplayRecorder`, `ReplaySampler`, `ReplayLines`, `ReplayFileName`, `FrameReader`; replay buffer clips: `ReplayRing`, `ClipBuilder`, `ClipWriter`, `ClipFileName`, `ClipStatus` |
| `com.rfl.replay.pitch` | The house/pitch capture that feeds replays: `PitchCapture`, `PitchScanner`, `PitchFloor`, `PitchObjects` |
| `com.rfl.teams` | Team assignment: `Teams`, `TeamMenu` |
| `sh.yumekui.toolkit.*` | Plugin-neutral pieces vendored into this plugin — see below |

### The toolkit

`sh.yumekui.toolkit` (under `src/main/java/sh/yumekui/toolkit`) is a set of small, RFL-agnostic pieces
— serial queues, a gzip NDJSON writer, triangle-mesh intersection, Swing sidebar widgets, and more
— pulled out so another RuneLite plugin can vendor the same code. It's plain source copied into
this repository, not a published dependency (the Plugin Hub builds each plugin from its own repo
and won't fetch one). See [`src/main/java/sh/yumekui/toolkit/README.md`](src/main/java/sh/yumekui/toolkit/README.md)
for what's in it and how to copy it into another plugin.

### Plugin Hub compliance

Every Hub rule this plugin has to meet, and the exact code that satisfies it, is tracked in
[`docs/plugin-hub-compliance.md`](docs/plugin-hub-compliance.md) — networking (there is none),
reflection and dynamic loading (none), injected Gson, `LinkBrowser` over `Desktop`, off-client-thread
IO, gameval constants, config-key stability, and the reviewer questions a submission is likely to
get, answered in advance.

### The recording format

The replay file's line-by-line format (`.rflr.gz`: gzipped newline-delimited JSON) is specified
outside this repository, in the **rfl-pages** project's replay format spec — that repository may
not be public, so ask the project owner for access if you need it. [`ReplayLines.java`](src/main/java/com/rfl/replay/ReplayLines.java)
is this plugin's side of that contract: every line shape it writes, in one place. A replay buffer
clip is an ordinary recording with one extra `hdr` field, `clip` (see
[Replay buffer clips](#replay-buffer-clips) and [`ClipBuilder.java`](src/main/java/com/rfl/replay/ClipBuilder.java)).

## Credits and licence

RFL Audit is licensed under the [BSD 2-Clause License](LICENSE).

The client's colour palette (`sh.yumekui.toolkit.model.Palette`, used by the model capture that
feeds replays) is an original implementation, written from public sources only: the packed 16-bit
colour layout documented by RuneLite's BSD-licensed `net.runelite.api.JagexColor`, the standard
HSL-to-RGB formula, and a brightness gamma that is the inverse of `JagexColor.rgbToHSL`. The file's
header cites them.
