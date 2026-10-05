# sh.yumekui.toolkit

Small, plugin-neutral pieces for RuneLite plugins, pulled out of RFL Audit. Nothing here knows
about RFL: no RFL class, setting or wording. Each type has javadoc saying what it does, which
thread it expects, and where its numbers come from.

It uses only the JDK, the RuneLite client API that every Plugin Hub plugin compiles against, and
the Gson, Lombok and SLF4J that come with it. It adds no dependency, does no networking, no
reflection, no JNI and no process launching, and does its file IO off the client thread, so a copy
stays inside the Plugin Hub rules.

## What is in it

| Package | Type | What it does |
|---|---|---|
| `concurrent` | `SerialQueue` | Runs queued steps one at a time, in order, on any executor, with one drain task and no lost wake-ups. A step that throws goes to a handler and the queue carries on. |
| `io` | `GzipNdjsonWriter` | One gzipped NDJSON file at a time on a daemon thread of its own: serialise-now or serialise-later lines, a sync flush every 2 s so a crash keeps the file readable, never-overwrite numbered names, a backlog cap that fails the file cleanly when the disk can't keep up, save progress for a UI, and `shutdown()` to end the thread. |
| `io` | `WriterState` | The writer's state: idle, writing, saving, saved, error. |
| `io` | `DailyJsonlAppender` | Appends JSON lines to `YYYY-MM-DD.jsonl` (local date) in a folder, in order, off the caller's thread. |
| `io` | `IoErrors` | A short reason for an IO failure ("access denied") instead of a message that is just the path. |
| `text` | `FileNameTemplate` | `{token}` templates for file names, made safe on Windows, macOS and Linux, plus `-2`, `-3` numbering. |
| `text` | `PlayerNames` | `sanitized(Player)` and `matchKey(name)`, the way RuneLite compares names. |
| `text` | `Html` | Escapes text for Swing HTML labels. |
| `time` | `FrameBudget` | Runs a long job a few steps per frame within a time budget, always at least one step. |
| `clipboard` | `Clipboard` | Copies text and returns false when the clipboard is busy, instead of throwing. |
| `geom` | `TriangleMesh` | A posed model's triangles in scene space and an exact triangle-triangle intersection (Moller 1997) with a sort-and-sweep broad phase. Its `Intersector` reuses buffers, so per-frame checks allocate nothing per candidate pair. |
| `model` | `ModelCapture` | Copies a model's arrays into compact geometry (hidden faces dropped, colours through `Palette`), rotates it, and keeps one id per model key. |
| `model` | `Palette` | The client's HSL16-to-RGB table at brightness 0.8, built once. Original implementation from the packed layout in RuneLite's `JagexColor`, the standard HSL-to-RGB formula and the inverse of `JagexColor.rgbToHSL`'s gamma (sources cited in its header). |
| `model` | `RenderableModels` | Finds the `Model` the client draws for a scene object's `Renderable`, the way the GPU plugin does. |
| `model` | `VertexSnapshot` | Saves a model's untouched vertices so they can be put back before each pose; a cached model then never drifts. |
| `overlay` | `TilePainter` | An outlined, lightly filled tile. |
| `overlay` | `MeshProjector` | Draws mesh triangles with each vertex projected at most once per frame into reused buffers. |
| `scene` | `PohDetector` | Whether the local player is in a player-owned house, using RuneLite's own POH template regions. |
| `scene` | `SceneStamps` | Stamps a stored scene tile with the scene it came from, so it is never drawn on a reloaded scene. |
| `swing` | `SidebarWidgets` | Fixed-height rows, truncating and fixed-width labels, height caps, a dot icon and a scrolling display, for side panels whose cards never change size. |
| `swing` | `ConfirmHeader` | A list header whose destructive button asks first, inline. |
| `swing` | `SelectableCard` | A count card that is also a keyboard-accessible switch. |
| `swing` | `ToggleSelection` | Click-to-toggle selection of one list row. |
| `swing` | `CopyFeedbackLabel` | A result line that clears itself after a few seconds. |

Tests for these live beside them under `src/test/java/sh/yumekui/toolkit`.

## Using it in another plugin

The Plugin Hub builds each plugin from its own repository and won't fetch a shared library, so the
toolkit is vendored: copied into each plugin's source tree.

1. Copy `src/main/java/sh/yumekui/toolkit` (and, if you want the tests, `src/test/java/sh/yumekui/toolkit`)
   into the other plugin at the same paths. Copy only the packages you use; a package depends only on
   the JDK, RuneLite and the packages listed below.
2. Keep the package name. Each Hub plugin loads in its own class loader, so the same classes in two
   plugins never clash. Never move them under `net.runelite.*`, which the client treats as built in.
3. Wire things up with Guice as usual. None of these types are singletons or injected themselves;
   create them in your own classes.
4. To share fixes, keep one source copy (this repository, or a toolkit repository later) and copy
   changes out, or use `git subtree`. Note the version or commit you copied in your plugin's README.

Dependencies between packages: `io` uses `concurrent` and `text`; `overlay` uses `geom`; `swing`'s
types use `SidebarWidgets`; `model` is self-contained apart from `ModelCapture` using `Palette`.

## Rules kept by every type

- Nothing blocks the client thread: file IO runs on an executor, and per-frame helpers reuse buffers.
- A thread created here (the gzip writer's) is a daemon and is ended by `shutdown()`.
- Gson is passed in, never created, so a plugin uses the client's configured instance.
- Numbers have names and say where they come from.

## Package name

`sh.yumekui.toolkit` is reverse-domain for `yumekui.sh`, a domain the owner holds. Keep that name
when vendoring the package into other plugins so every copy stays the same toolkit.
