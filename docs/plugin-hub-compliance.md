# RFL Audit: Plugin Hub compliance checklist

Checked on 2026-10-04 and refreshed on 2026-11-03 against the code on branch `work`. Evidence names
classes and methods rather than line numbers, which move; the few line numbers left were
regenerated on the refresh date. Two
checks run on every build: `HubRulesTest` (forbidden APIs in all main sources) and
`HubPackagingTest` (listing icon, plugin properties, no service-loader file).

## Hub rules

| Rule | Status | Evidence |
|---|---|---|
| No networking, and no third-party server | Pass. The plugin has no network code at all, so the Hub's opt-in and IP-warning rule doesn't apply. | `HubRulesTest` rejects `okhttp3`, `java.net`, `javax.net`, `HttpURLConnection` and sockets in every main source. `runelite-plugin.properties` ends "Nothing is sent anywhere." |
| No reflection, JNI/JNA, `ProcessBuilder`, `Runtime.exec`, serialization or dynamic class loading | Pass | `HubRulesTest`, with a self-check that each rule catches a sample (`HubRulesTest.eachRuleCatchesWhatItIsFor`) |
| Gson injected, never `new Gson()` | Pass | the `BareBody`, `PluginLog`, `RflPanelController` and `ReplayRecorder` constructors take the injected `Gson`; `HubRulesTest` rejects `new Gson()` |
| Links opened with `LinkBrowser`, never `Desktop` | Pass | `RflPanelController` (Open folder passes a plain path to `LinkBrowser.open`); `HubRulesTest` rejects `java.awt.Desktop` |
| No IO on the client thread | Pass | Day files: `DailyJsonlAppender`, run through a `SerialQueue` on the injected executor. Replays: `GzipNdjsonWriter` runs every file step on its own thread (`GzipNdjsonWriter`, its own daemon thread). Replay buffer clips: the buffer lives on that same writer thread (`ReplayRing`, the writer's line tap), the client thread only queues a snapshot step (`ReplayRecorder.saveClip`), and the clip is cut and written on its own thread (`ClipWriter`: folder created and a partial file deleted on failure on that thread). The clips folder's Open folder creates it on the executor (`RflPanelController`, Open folder). Copy plugin history reads the day file on the executor (`PluginLog.readToday`, called from `RflPanelController`). Open folder creates the folder on the executor, never on the EDT. The bundled kit table is read in `startUp`, which runs off the client thread (`RflPlugin.startUp` calling `BareBody.load`). |
| Never block the client thread | Pass | The replay writer never waits: a disk that falls 64 MiB behind fails the file instead of growing the queue (`GzipNdjsonWriter.MAX_PENDING_BYTES`). Client exit waits at most 5 s for the gzip trailer (`RflPlugin.onClientShutdown`, `EXIT_WAIT_SECONDS`), a pending clip included. Saving a clip costs the client thread one queued step, about 7 microseconds (`ReplayClipTest.savingAClipCostsTheCallerOneQueuedStep`). |
| Threads the plugin starts are cleaned up | Pass | Two daemon threads, each started on first use: `rfl-replay-writer` (`GzipNdjsonWriter`) and `rfl-clip-writer` (`ClipWriter`). Both are ended by `ReplayRecorder.shutdown()` / `stopAndReleaseWriter()`, the clip writer after the replay writer has handed over its last clip, from plugin shut-down (`RflPlugin.shutDown`) and client exit (`RflPlugin.onClientShutdown`). |
| Gameval constants, no magic item or spot anim ids | Pass | `Handegg.java` (`ItemID` and `SpotanimID` from `net.runelite.api.gameval`) |
| Third-party features opt-in, everything off by default | Pass. Owner ruling: every switch defaults to off. | every `boolean` item in `RflConfig` returns `false`; `RflConfigTest` checks the source so a new switch can't default on. |
| Config keys stable | Pass | No key renamed. `reportContacts` keeps its old name for Detect contacts (`RflConfig` class comment and the `reportContacts` item). New keys: `recordOverheadChat`, `hideInGameHighlights` (off by default; removes the overlays while on), and for replay buffer clips `saveClipHotkey` (a `Keybind`, unset by default), `clipSeconds` (30-300, default 180) and `clipFileName`. Keys from removed features (`installId`, `enableReporting`, `debugLogging`, `showDebugPanel`, `observerMode`, `matchCode`, `team`, `reportPlugins`, `reportNearby`, `logPluginStats`, `detectInterceptions`, `interceptionChatMessage`, `interceptionColor`, `highlightInterceptions`, `contactMode`, `hitboxView`, `showOverlap`) are no longer read; values users still have stored are harmless and are not deleted. |
| Shut-down undoes start-up | Pass | `RflPlugin.shutDown`: the clip hotkey unregistered from the `KeyManager` (registered in `startUp`, `:186`), overlays removed, team listener cleared, detection and incomplete state reset on the client thread, the replay closed and its writer thread ended, the panel removed on the EDT. |
| Menu entries only added, never reordered or hidden | Pass | `TeamMenu.onMenuEntryAdded` adds `RUNELITE_PLAYER` entries, only inside a house and only on player options, at index 1 (just above Cancel, so they are never the left-click action), and a click only sets a local team label. Duplicate check: `TeamMenu.alreadyAdded`. |
| Listing icon | Pass | `/icon.png`, 48x72, 1.1 KiB; `HubPackagingTest.theListingIconIsAt48By72AtTheRepositoryRoot` |
| Plugin properties | Pass | `runelite-plugin.properties`: `displayName` matches `@PluginDescriptor(name = "RFL Audit")`, `plugins=com.rfl.RflPlugin`, description and tags updated; `HubPackagingTest` |
| Packaging | Pass | No `META-INF/services` file (`HubPackagingTest`); `build/` and `.gradle` ignored; `build.gradle` follows the example-plugin template with no extra runtime dependencies (the `runClient` and `benchmark` tasks are local only). |
| No game automation | Pass | No `invokeMenuAction`, key or mouse injection. Grep: none in `src/main`. |
| Licence | Pass | `LICENSE`, BSD-2-Clause |
| Shared code stays inside the jar | Pass | `sh.yumekui.toolkit` is source in this repository, compiled into the plugin; it uses only the JDK, the RuneLite API and the Gson, Lombok and SLF4J the client provides. It lives outside `net.runelite.*`. See `src/main/java/sh/yumekui/toolkit/README.md`. |

## Reviewer questions

| # | Question | Answer, with evidence |
|---|---|---|
| Q1 | Recording other players' overhead chat? | Off by default behind its own switch, Record overhead chat, whose description warns that a shared replay contains other players' public chat (`RflConfig.recordOverheadChat`, checked in `RflPlugin` before the chat line is recorded). It needs Record replays on and a house. Text is public overhead chat only, tags stripped, at most 80 characters (`ReplayLines.CHAT_MAX` and the plain-text helper below it), written only to local replay files. |
| Q2 | Does Show hitboxes reveal anything? | It draws the triangles of each player's model the client is already rendering (`HitboxOverlay`), like core's hull and outline overlays. Display only, off by default, and touching triangles fill only for handegg pairs on opposite teams. |
| Q3 | Why right-click entries on players? | Additive team labels, inside a house only (Hub rule row above). |
| Q4 | Why read the installed plugin list? | Only this client's own `PluginManager`, through public API (`PluginSnapshotter`), written to local day files on entering and leaving a house and on each toggle (`RflPlugin.setInPoh`, `RflPlugin.onPluginChanged`). The user decides whether to paste it with Copy plugin history. No other player's plugins are known. |
| Q5 | Is collision or incomplete detection an advantage? | It rules a player-run minigame (handegg in a house) from what is drawn on screen. No combat, PvP, skilling or economy effect; an incomplete only posts an optional local game message. |
| Q6 | Does `identkits.json` hold cache data? | A small table of identity-kit body part to model id mappings, used by the experimental Bare body hitbox source and to record each player's bare body in replays (`src/main/resources/com/rfl/contact/identkits.json`, generated once from the OpenRS2 OSRS cache, cache 2727, index 2 group 3, by a one-off local script that is not part of this repo or the build; the file's `source` field records it and the README credits it). It is read only by the Bare body hitbox option and the bare-body replay lines. It can be removed together with those two features if asked. |
| Q7 | Where does `Palette` come from? | An original implementation, written from public sources only: the packed 16-bit layout documented by RuneLite's BSD-licensed `JagexColor` (runelite-api 1.13.1), the standard HSL-to-RGB formula, and a brightness gamma that inverts `JagexColor.rgbToHSL`. The header cites them (the header of `Palette.java`). `PaletteTest` round-trips colours through `JagexColor.rgbToHSL` to within one packed step. RuneLite's API has no HSL-to-RGB conversion to use instead (`JagexColor` has only RGB-to-HSL). |
| Q8 | Docs that describe uploads and a game browser? | Deleted (the old planning documents). `docs/rfl-game-model.md` now says the plugin is local-only. |
| Q9 | Team tiles drawn outside a house? | Only while the RFL panel's Teams view is open on screen and the player is in a house (`TeamTileOverlay.draws`), display only. Like every other overlay in the plugin, it draws nothing outside a house. |

## Reviewer summary (PR body)

RFL Audit helps refs of RuneScape Football League (handegg, played in a player-owned house) rule collisions and incompletes, keeps your own plugin list as a local log, and records house visits for the RFL replay viewer. Everything stays on your computer.

- **Network:** none. There is no network code at all (no OkHttp, `java.net` or sockets; `HubRulesTest` fails the build on any). No third-party server, so no IP warning applies.
- **Defaults:** every switch defaults to off (`RflConfigTest` checks the source so a new one can't land on). The one thing that always runs is the local log of the user's **own** plugin list (below).
- **Other players' data:** only what the client already draws, and only to local files when the user turns it on.
  - Detection reads each in-view player's rendered model and position inside a house (`ContactDetector`), only with Detect contacts on and Team A / Team B assigned.
  - Replays (Record replays, off by default) save positions, poses, appearance models, spot anims and true tiles of players in the house to `.runelite/rfl/replays/*.rflr.gz`.
  - Replays also store each player's bare body: their identity-kit base models posed like them, built with `client.loadModelData` / `mergeModels` from kit ids the client already exposes (`BareBody`). That is the default clothed base body, not anything hidden, so the viewer can test contacts without armour.
  - Overhead chat is recorded only with Record overhead chat on (off by default). It is public overhead text only, tags stripped, at most 80 characters, and the setting's description warns that a shared replay contains other players' public chat.
  - Nothing is ever sent. Sharing a file is a manual user action.
- **Plugin list:** only this client's own, through public API (`PluginManager.getPlugins()` / `isPluginEnabled`, `ExternalPluginManager.getInternalName`). It is logged on entering and leaving a house and on each toggle while logged in, to `.runelite/rfl/plugins/YYYY-MM-DD.jsonl`. No other player's plugins can be known. League refs ask players to share this log voluntarily (Copy plugin history in the panel).
- **Menu entries:** local-only `RUNELITE_PLAYER` entries (`RFL: Team A / Team B / Unassign`) added to other players' menus, only inside a house, only with Right-click team options on (off by default). They are inserted at index 1, just above Cancel, so they are never the left-click action. The click only sets a local team label (`TeamMenu`). Nothing is removed, reordered or sent to the server.
- **Overlays:** off by default, display only. Show hitboxes draws the triangles of models the client is already rendering (like core hull/outline). Touching triangles and tile highlights mark contacts in a player-run minigame. Team tiles show only in a house, and only while the panel's Teams view is open. **Hide in-game highlights** removes every overlay at once (`RflOverlays.setShown`) so a player can keep detection and logging running without any on-screen help. No PvP, PvM, skilling or economy effect: no combat info, no prediction, nothing that isn't already on screen.
- **Chat messages:** optional local `GAMEMESSAGE` lines (Collision chat message, Chat message for incompletes), off by default, via `client.addChatMessage`. Only the user sees them; nothing is typed or sent.
- **Replay clips:** a hotkey (unset by default) or panel button writes the last 30-300 s of the open local recording as its own file under `.runelite/rfl/replays/snapshot reviews`. Local only.
- **Disk:** only under `.runelite/rfl/` (collisions, plugin log, replays, clips). Every write and read runs off the client thread: day files on the injected executor through a serial queue, replays and clips on two named daemon threads that are shut down on `shutDown()` and client exit (exit waits at most 5 s for the gzip trailer).
- **Bundled data:** `identkits.json`, identity-kit to model-id mappings generated once from the OpenRS2 OSRS cache (source line inside the file), used for bare-body hitboxes and replays. The colour palette is an original implementation from RuneLite's BSD `JagexColor` and the standard HSL formula (cited in `Palette.java`).
- **Reflection in tests:** test-only `java.lang.reflect.Proxy` fakes for `Client`, `Player` and `WorldView` (`CollisionChatMessageTest`, `PitchScannerTest`, `ReplayClipTest`, `TeamMenuTest`). Nothing under `src/main` uses reflection; `HubRulesTest` scans every main source.
- **shutDown:** unregisters the hotkey, removes all four overlays, clears the team listener, resets detection on the client thread, closes the replay and ends its writer threads, and removes the panel on the EDT.

## Owner ruling recorded

Collision detection builds a mesh for every Team A and Team B player each frame while detection
runs, holder or not, because the incomplete rule needs the receiver's contacts during the flight
(`ContactDetector`, `docs/rfl-game-model.md` §3). Unassigned players are skipped; they
can never pass the team gate.

## Replay format

Replays now also carry each player's bare body: `bare` model lines and `pb` rows, an additive change
the RFL replay viewer reads (older viewers ignore them). Spec deviations the review listed are
documentation items for the replay spec in `rfl-pages`, not plugin changes: `hdr` has no plugin
version, `ball` lines carry `sc` (the projectile's start cycle), the `ev` collision example is
stale, §2.1 still names an old interception list, and §2.3 says "part 2" where the code uses part 1.

## Still to verify in game

The plugin can't be run here. Before submitting, the owner should run `./gradlew runClient` (macOS)
or `gradlew.bat runClient` (Windows) and check the in-game test plan in the refactor report:
detection and incompletes in a house, the bare-body option, replays (start, stop, a reload, a failed
disk), the panel, and that client exit finishes the replay file.
