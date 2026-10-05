# RFL Audit: Plugin Hub compliance checklist

Checked on 2026-10-04 against the code on branch `work`. Line numbers refer to that code. Two
checks run on every build: `HubRulesTest` (forbidden APIs in all main sources) and
`HubPackagingTest` (listing icon, plugin properties, no service-loader file).

## Hub rules

| Rule | Status | Evidence |
|---|---|---|
| No networking, and no third-party server | Pass. The plugin has no network code at all, so the Hub's opt-in and IP-warning rule doesn't apply. | `HubRulesTest` rejects `okhttp3`, `java.net`, `javax.net`, `HttpURLConnection` and sockets in every main source. `runelite-plugin.properties` ends "Nothing is sent anywhere." |
| No reflection, JNI/JNA, `ProcessBuilder`, `Runtime.exec`, serialization or dynamic class loading | Pass | `HubRulesTest`, with a self-check that each rule catches a sample (`src/test/java/com/rfl/HubRulesTest.java:72`) |
| Gson injected, never `new Gson()` | Pass | `BareBody.java:109`, `PluginLog.java:77`, `RflPanelController.java:96`, `ReplayRecorder` (injected constructor); `HubRulesTest` rejects `new Gson()` |
| Links opened with `LinkBrowser`, never `Desktop` | Pass | `RflPanelController.java:292` (Open folder opens a plain path); `HubRulesTest` rejects `java.awt.Desktop` |
| No IO on the client thread | Pass | Day files: `DailyJsonlAppender.java:53-54`, run through a `SerialQueue` on the injected executor. Replays: `GzipNdjsonWriter` runs every file step on its own thread (`GzipNdjsonWriter.java:150`, open queued at `:220`, writes at `:523`). Copy plugin history reads the day file on the executor (`RflPanelController.java:303`, `PluginLog.java:114-118`). Open folder creates the folder on the executor (`RflPanelController.java:280-292`). The bundled kit table is read in `startUp`, which runs off the client thread (`RflPlugin.java:141`, `BareBody.java:122`). |
| Never block the client thread | Pass | The replay writer never waits: a disk that falls 64 MiB behind fails the file instead of growing the queue (`GzipNdjsonWriter.java:75`, `:306`). Client exit waits at most 5 s for the gzip trailer (`RflPlugin.java:342`). |
| Threads the plugin starts are cleaned up | Pass | One daemon thread, `rfl-replay-writer` (`GzipNdjsonWriter.java:150-154`), ended by `shutdown()` (`:176`) from plugin shut-down (`RflPlugin.java:184`) and client exit (`RflPlugin.java:334`). |
| Gameval constants, no magic item or spot anim ids | Pass | `Handegg.java:17` (`ItemID`), `:21-22` (`SpotanimID`) |
| Third-party features opt-in, everything off by default | Pass. Owner ruling: every switch defaults to off. | `RflConfig.java` returns `false` for every boolean (lines 66, 84, 123, 140, 190, 207, 241, 257, 272, 306, 339); `RflConfigTest` checks the source so a new switch can't default on. |
| Config keys stable | Pass | No key renamed. `reportContacts` keeps its old name for Detect contacts (`RflConfig.java:16`, `:75`). New key: `recordOverheadChat` (`:330`). Keys from removed features (`installId`, `enableReporting`, `debugLogging`, `showDebugPanel`, `observerMode`, `matchCode`, `team`, `reportPlugins`, `reportNearby`, `logPluginStats`, `detectInterceptions`, `interceptionChatMessage`, `interceptionColor`, `highlightInterceptions`, `contactMode`, `hitboxView`, `showOverlap`) are no longer read; values users still have stored are harmless and are not deleted. |
| Shut-down undoes start-up | Pass | `RflPlugin.java:171-186`: overlays removed, team listener cleared, detection and incomplete state reset on the client thread, the replay closed and its writer thread ended, the panel removed on the EDT. |
| Menu entries only added, never reordered or hidden | Pass | `TeamMenu.java:61-66` adds `RUNELITE_PLAYER` entries, only inside a house and only on player options, and a click only sets a local team label. Duplicate check: `TeamMenu.java:78`. |
| Listing icon | Pass | `/icon.png`, 48x72, 1.1 KiB; `HubPackagingTest.theListingIconIsAt48By72AtTheRepositoryRoot` |
| Plugin properties | Pass | `runelite-plugin.properties`: `displayName` matches `@PluginDescriptor(name = "RFL Audit")`, `plugins=com.rfl.RflPlugin`, description and tags updated; `HubPackagingTest` |
| Packaging | Pass | No `META-INF/services` file (`HubPackagingTest`); `build/` and `.gradle` ignored; `build.gradle` follows the example-plugin template with no extra runtime dependencies (the `runClient` and `benchmark` tasks are local only). |
| No game automation | Pass | No `invokeMenuAction`, key or mouse injection. Grep: none in `src/main`. |
| Licence | Pass | `LICENSE`, BSD-2-Clause |
| Shared code stays inside the jar | Pass | `sh.yumekui.toolkit` is source in this repository, compiled into the plugin; it uses only the JDK, the RuneLite API and the Gson, Lombok and SLF4J the client provides. It lives outside `net.runelite.*`. See `src/main/java/sh/yumekui/toolkit/README.md`. |

## Reviewer questions

| # | Question | Answer, with evidence |
|---|---|---|
| Q1 | Recording other players' overhead chat? | Off by default behind its own switch, Record overhead chat, whose description warns that a shared replay contains other players' public chat (`RflConfig.java:330`, checked at `RflPlugin.java:251`). It needs Record replays on and a house. Text is public overhead chat only, tags stripped, at most 80 characters (`ReplayLines.java:27`, `:208-216`), written only to local replay files. |
| Q2 | Does Show hitboxes reveal anything? | It draws the triangles of each player's model the client is already rendering (`HitboxOverlay`), like core's hull and outline overlays. Display only, off by default, and touching triangles fill only for handegg pairs on opposite teams. |
| Q3 | Why right-click entries on players? | Additive team labels, inside a house only (Hub rule row above). |
| Q4 | Why read the installed plugin list? | Only this client's own `PluginManager`, through public API (`PluginSnapshotter.java:40`, `:73`), written to local day files on entering and leaving a house and on each toggle (`RflPlugin.java:289-295`, `:305-313`). The user decides whether to paste it with Copy plugin history. No other player's plugins are known. |
| Q5 | Is collision or incomplete detection an advantage? | It rules a player-run minigame (handegg in a house) from what is drawn on screen. No combat, PvP, skilling or economy effect; an incomplete only posts an optional local game message. |
| Q6 | Does `identkits.json` hold cache data? | A small table of identity-kit body part to model id mappings, used only by the experimental Bare body hitbox source (`src/main/resources/com/rfl/contact/identkits.json`, generated once from the OpenRS2 OSRS cache by a one-off local script that is not part of this repo or the build). It can be removed together with that option if asked. |
| Q7 | Where does `Palette` come from? | An original implementation, written from public sources only: the packed 16-bit layout documented by RuneLite's BSD-licensed `JagexColor` (runelite-api 1.13.1), the standard HSL-to-RGB formula, and a brightness gamma that inverts `JagexColor.rgbToHSL`. The header cites them (`Palette.java:3-16`). `PaletteTest` round-trips colours through `JagexColor.rgbToHSL` to within one packed step. RuneLite's API has no HSL-to-RGB conversion to use instead (`JagexColor` has only RGB-to-HSL). |
| Q8 | Docs that describe uploads and a game browser? | Deleted (the old planning documents). `docs/rfl-game-model.md` now says the plugin is local-only. |
| Q9 | Team tiles drawn outside a house? | Only while the RFL panel's Teams view is open on screen (`TeamTileOverlay.java:74`), display only, like core Player Indicators. Not gated on the house, by design: assignments are by name. |

## Owner ruling recorded

Collision detection builds a mesh for every Team A and Team B player each frame while detection
runs, holder or not, because the incomplete rule needs the receiver's contacts during the flight
(`ContactDetector.java:114-118`, `docs/rfl-game-model.md` §3). Unassigned players are skipped; they
can never pass the team gate.

## Replay format

The replay file format is unchanged byte for byte: a scripted recording of every line type through
the writer was compared before and after every change. Spec deviations the review listed are
documentation items for the replay spec in `rfl-pages`, not plugin changes: `hdr` has no plugin
version, `ball` lines carry `sc` (the projectile's start cycle), the `ev` collision example is
stale, §2.1 still names an old interception list, and §2.3 says "part 2" where the code uses part 1.

## Still to verify in game

The plugin can't be run here. Before submitting, the owner should run `./gradlew runClient` (macOS)
or `gradlew.bat runClient` (Windows) and check the in-game test plan in the refactor report:
detection and incompletes in a house, the bare-body option, replays (start, stop, a reload, a failed
disk), the panel, and that client exit finishes the replay file.
