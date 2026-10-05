package com.rfl.replay;

import sh.yumekui.toolkit.text.PlayerNames;
import sh.yumekui.toolkit.io.GzipNdjsonWriter;

import com.rfl.RflConfig;
import com.rfl.contact.Collision;
import com.rfl.incomplete.Incomplete;
import com.rfl.log.CollisionLog;
import com.rfl.log.PluginEntry;
import com.rfl.replay.pitch.Loc;
import com.rfl.replay.pitch.PitchCapture;
import com.rfl.replay.pitch.PitchScanner;
import com.rfl.teams.Teams;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.client.RuneLite;

/**
 * Records each player-owned house visit to {@code RUNELITE_DIR/rfl/replays/<fileName>}, a gzipped
 * NDJSON file the RFL replay viewer reads, while Record replays is on. Nothing is sent anywhere.
 *
 * <p>Each ClientTick it reads every player's pose and spot anims and every handegg projectile
 * ({@link FrameReader}) into a {@link ReplaySampler}; each GameTick it adds a {@code tick} line,
 * appearances and true tiles. Finished collisions and incompletes arrive through {@link #onEvent},
 * overhead chat through {@link #onOverheadText}, and the local user's own plugin list and toggles
 * through {@link #onPlugins}. Every line goes to a {@link GzipNdjsonWriter}, which does the IO off the
 * client thread. The line shapes are in {@link ReplayLines}.
 *
 * <p>Lifecycle: a file opens when {@link #records} turns true and closes when it turns false, on
 * HOPPING / LOGIN_SCREEN, and on {@link #stop}. The start is spread over ClientTicks, one piece
 * each: {@code hdr}, then {@code plugins}, then the three pitch scan stages ({@link PitchScanner}),
 * then the house models ({@link PitchCapture}). A file stays open through LOADING, which queues a
 * fresh pitch for the first logged-in frame after the load. Each file gets a fresh sampler.
 *
 * <p>Threads: all state here is client-thread only. The writer's IO is its own.
 */
@Slf4j
@Singleton
public final class ReplayRecorder
{
    /**
     * Per-ClientTick time budget for the house model capture: it is spread over as many ClientTicks
     * as it needs, so a house of a few hundred locs never stalls one frame.
     */
    static final long LOC_BUDGET_NANOS = 1_000_000L;
    /** The writer thread's name, as it shows in a thread dump. */
    private static final String WRITER_THREAD = "rfl-replay-writer";

    private final RflConfig config;
    private final GzipNdjsonWriter writer;
    private final Path dir;
    /** For {@link #warmUp}; null in tests. */
    private final Gson gson;
    private final RecorderStats stats = new RecorderStats();
    private final FrameReader reader = new FrameReader(stats);
    /** The pitch line's spread-out house model capture; it writes the pitch and locs lines. */
    private final PitchCapture pitch;
    private final PitchScanner scanner;

    /** Non-null while a file is open. */
    private ReplaySampler sampler;
    private int startCycle;
    /** Latest game cycle seen by {@link #onClientTick}, so {@link #onEvent} doesn't need the client. */
    private int lastCycle;
    /** A LOADING happened while open: the next logged-in frame starts a fresh pitch scan. */
    private boolean pitchPending;
    /** The {@code plugins} line is still to write (the ClientTick after open). */
    private boolean pluginsDue;
    /** Set by {@link #shutdown}: the client is exiting, so no new file may open. */
    private boolean shutDown;
    /**
     * Set when the writer failed mid-recording (a full disk, the disk falling behind): no new file
     * opens until recording is switched off or the house left, so a failing disk isn't retried
     * every frame.
     */
    private boolean haltedAfterFailure;
    /** Each player's team, for {@code team} lines; never null. */
    private Function<String, Teams.Team> teamSource = name -> null;
    /** The local user's own plugin list for the {@code plugins} line; may be null or return null. */
    private Supplier<List<PluginEntry>> pluginSource;
    /**
     * Loc id to object name, per file: an impostor's name depends on varbits (as drawn now), so a
     * new file looks names up again.
     */
    private final Map<Integer, String> locNames = new HashMap<>();
    /** For the panel: when the open file opened, and the last file's model count. */
    private long openedAtMs;
    private int lastModels;

    @Inject
    ReplayRecorder(RflConfig config, Gson gson)
    {
        this(config, new GzipNdjsonWriter(gson, ReplayFileName.SUFFIX, WRITER_THREAD), RuneLite.RUNELITE_DIR.toPath().resolve("rfl").resolve("replays"), gson);
    }

    /** Tests: a given writer and folder, and no warm-up. */
    ReplayRecorder(RflConfig config, GzipNdjsonWriter writer, Path dir)
    {
        this(config, writer, dir, null);
    }

    private ReplayRecorder(RflConfig config, GzipNdjsonWriter writer, Path dir, Gson gson)
    {
        this.gson = gson;
        this.config = config;
        this.writer = writer;
        this.dir = dir;
        this.pitch = new PitchCapture(new PitchCapture.Sink()
        {
            @Override
            public void models(List<Map<String, Object>> lines)
            {
                writeAll(lines);
            }

            @Override
            public void pitch(Map<String, Object> line)
            {
                // About 200 KB of JSON: serialised on the writer's thread, never on the client thread.
                writer.writeDeferred(line, false);
            }

            @Override
            public void locs(Map<String, Object> line)
            {
                writer.writeDeferred(line, false);
            }
        }, LOC_BUDGET_NANOS, System::nanoTime);
        this.scanner = new PitchScanner(pitch, stats);
    }

    /**
     * Any thread but the client's (plugin start-up): runs the replay code once on made-up data so
     * the first recorded frames don't pay first-use costs ({@link ReplayWarmUp}).
     */
    public void warmUp()
    {
        if (gson != null)
        {
            ReplayWarmUp.run(gson);
        }
    }

    /** Recording runs with Record replays on, logged in, inside a POH. */
    static boolean records(boolean recordReplays, boolean loggedIn, boolean inPoh)
    {
        return recordReplays && loggedIn && inPoh;
    }

    /** Client thread: whether a replay file is open and being written (false after a disk failure). */
    public boolean recording()
    {
        return sampler != null && writer.isOpen();
    }

    /** Any thread: the writer's state, without building a {@link ReplayStatus}. */
    public ReplayState state()
    {
        return ReplayState.of(writer.state());
    }

    /**
     * Client thread: the replay card's state. The writer's state is read through volatiles, so a
     * save that finishes on the writer's thread after {@link #stop} still shows here.
     */
    public ReplayStatus status(long nowMs)
    {
        switch (ReplayState.of(writer.state()))
        {
            case RECORDING:
                if (sampler == null)
                {
                    return ReplayStatus.IDLE;
                }
                return new ReplayStatus(ReplayState.RECORDING, writer.fileName(), Math.max(0L, nowMs - openedAtMs),
                    writer.fileBytes(), sampler.modelsCaptured(), 0.0, null, 0L);
            case SAVING:
                return new ReplayStatus(ReplayState.SAVING, writer.fileName(), 0L, writer.fileBytes(), lastModels,
                    writer.progress(), null, 0L);
            case SAVED:
                return new ReplayStatus(ReplayState.SAVED, writer.fileName(), 0L, writer.savedBytes(), lastModels,
                    1.0, null, writer.savedAtMs());
            case ERROR:
                return new ReplayStatus(ReplayState.ERROR, writer.fileName(), 0L, 0L, lastModels, 0.0, writer.error(),
                    0L);
            default:
                return ReplayStatus.IDLE;
        }
    }

    /** Client thread, every ClientTick: opens or closes per {@link #records}, then samples the frame. */
    public void onClientTick(Client client, boolean inPoh)
    {
        if (shutDown)
        {
            return;
        }
        long start = System.nanoTime();
        GameState state = client.getGameState();
        // LOADING counts as logged in so a scene reload inside the house doesn't split the file.
        boolean loggedIn = state == GameState.LOGGED_IN || state == GameState.LOADING;
        if (!records(config.recordReplays(), loggedIn, inPoh))
        {
            haltedAfterFailure = false;
            stop();
            return;
        }
        if (sampler != null && !writer.isOpen())
        {
            // The writer failed: stop sampling now rather than building lines it would drop.
            haltedAfterFailure = true;
            stop();
            return;
        }
        WorldView view = client.getTopLevelWorldView();
        if (state != GameState.LOGGED_IN || haltedAfterFailure || view == null)
        {
            return;
        }
        lastCycle = client.getGameCycle();
        stats.startTick();
        stepStart(client);

        long sampleStart = System.nanoTime();
        writeAll(ReplayLines.withTeams(sampler.frame(lastCycle, reader.players(view), reader.balls(client)),
            teamSource));
        long end = System.nanoTime();
        stats.endTick(end - start, end - sampleStart, lastCycle, sampler.lastFrameNewModels(),
            sampler.lastModelNanos());
    }

    /**
     * One piece of the spread-out start per ClientTick: open (hdr), then plugins, then a pitch scan
     * stage; the house capture steps only on ticks with none of those.
     */
    private void stepStart(Client client)
    {
        if (sampler == null)
        {
            open(client);
            return;
        }
        if (pitchPending)
        {
            pitchPending = false;
            scanner.start();
        }
        if (pluginsDue)
        {
            writePlugins();
        }
        else if (scanner.scanning())
        {
            scanner.step(client, lastCycle, sampler);
        }
        else if (pitch.pending())
        {
            long captureStart = System.nanoTime();
            pitch.step();
            stats.addCaptureNanos(System.nanoTime() - captureStart);
        }
    }

    /** Client thread, every GameTick: a {@code tick} line, changed appearances and changed true tiles. */
    public void onGameTick(Client client)
    {
        WorldView view = client.getTopLevelWorldView();
        if (sampler == null || view == null)
        {
            return;
        }
        List<Appearance> appearances = new ArrayList<>();
        List<TrueTile> trueTiles = new ArrayList<>();
        reader.tickState(view, appearances, trueTiles);
        writeAll(sampler.tick(client.getGameCycle(), client.getTickCount(), appearances, trueTiles));
    }

    /** Client thread: closes on HOPPING / LOGIN_SCREEN; a LOADING while open queues a new pitch. */
    public void onGameStateChanged(GameState state)
    {
        if (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN)
        {
            stop();
        }
        else if (state == GameState.LOADING && sampler != null)
        {
            pitchPending = true;
        }
    }

    /** Client thread (the {@link CollisionLog} listener): one finished Collision or Incomplete. */
    public void onEvent(Object collisionOrIncomplete)
    {
        if (sampler != null && collisionOrIncomplete != null)
        {
            writer.write(ReplayLines.event(lastCycle, collisionOrIncomplete));
        }
    }

    /**
     * Client thread (OverheadTextChanged): public chat shown above a player's head, as a
     * {@link ReplayLines#chat} line for the viewer to draw over that player. Only while a file is
     * open and only for a player the file is already sampling.
     *
     * @param cycle the client cycle, the clock {@code f} lines use
     * @param name the player's sanitized name
     */
    public void onOverheadText(int cycle, String name, String text)
    {
        Map<String, Object> line = ReplayLines.chat(sampler, cycle, name, text);
        if (line != null)
        {
            writer.write(line);
        }
    }

    /** Client thread: where {@code team} lines get each player's team ({@link Teams#team}). */
    public void setTeamSource(Function<String, Teams.Team> source)
    {
        teamSource = source == null ? name -> null : source;
    }

    /**
     * Client thread: a player's team changed mid-recording; writes a {@code team} line for them if
     * the open file is sampling them. {@code name} is matched as {@link PlayerNames#matchKey}, so formatting
     * differences don't matter.
     */
    public void onTeamChanged(String name)
    {
        if (sampler != null)
        {
            writeAll(ReplayLines.teams(sampler, lastCycle, name, teamSource));
        }
    }

    /** Client thread: Clear teams; a {@code team} line with {@code null} for every sampled player. */
    public void onTeamsCleared()
    {
        if (sampler != null)
        {
            writeAll(ReplayLines.teams(sampler, lastCycle, null, name -> null));
        }
    }

    /**
     * Client thread: supplies the local user's own plugin list for the {@code plugins} line written
     * at file open. Null, or a source returning null, writes no line.
     */
    public void setPluginSource(Supplier<List<PluginEntry>> source)
    {
        this.pluginSource = source;
    }

    /**
     * Client thread: writes one plugin line ({@link ReplayLines#plugins} or
     * {@link ReplayLines#pluginToggle}) to the open file. Nothing happens when no file is open.
     */
    public void onPlugins(Object line)
    {
        if (sampler != null && line != null)
        {
            writer.write(line);
        }
    }

    /** Latest game cycle seen, for stamping plugin lines. */
    public int cycle()
    {
        return lastCycle;
    }

    /**
     * Client thread, on client exit: closes the open file and never opens another (the client keeps
     * ticking for a moment after ClientShutdown, and a file opened then would be cut off), then ends
     * the writer's thread. The future completes once everything queued is on disk.
     */
    public CompletableFuture<Void> shutdown()
    {
        shutDown = true;
        stop();
        return writer.shutdown();
    }

    /**
     * Client thread, plugin shut-down: closes the open file, then ends the writer's thread once
     * everything queued is on disk. The next file starts a new thread.
     */
    public CompletableFuture<Void> stopAndReleaseWriter()
    {
        stop();
        return writer.shutdown();
    }

    /**
     * Client thread: closes the open file, if any. Safe to call when nothing is open. The future
     * completes once the gzip trailer is on disk (at once when nothing was open).
     */
    CompletableFuture<Void> stop()
    {
        if (sampler == null)
        {
            return CompletableFuture.completedFuture(null);
        }
        // Stopped mid-capture: the pitch goes out with the locs read so far, so the file has one.
        pitch.stop();
        lastModels = sampler.modelsCaptured();
        Runnable summary = closeSummary();
        CompletableFuture<Void> closed = writer.close();
        // Queued behind the close, so the writer's byte counts are final when it runs.
        writer.enqueue(summary);
        sampler = null;
        pitchPending = false;
        pluginsDue = false;
        scanner.stop();
        pitch.reset();
        return closed;
    }

    /**
     * The one performance summary per replay, always on: the plugin's only stutter diagnostic. The
     * recorder's numbers are taken now; the writer's when the returned step runs.
     */
    private Runnable closeSummary()
    {
        int cycles = lastCycle - startCycle;
        long averageMicros = stats.averageTickMicros();
        String steady = stats.steady();
        int models = sampler.modelsCaptured();
        int failures = stats.captureFailures();
        String locs = stats.locs(pitch);
        String worst = stats.worst();
        return () -> log.info("RFL replay closed {} cycles={} bytes={} avgClientTickMicros={}"
                + " {} models={} modelLines={} modelBytes={} avgModelLineBytes={} captureFailures={}"
                + " {} {}",
            writer.created(), cycles, writer.bytesWritten(), averageMicros, steady, models, writer.deferredLines(),
            writer.deferredBytes(),
            writer.deferredLines() == 0 ? 0 : writer.deferredBytes() / writer.deferredLines(), failures,
            locs, worst);
    }

    private void open(Client client)
    {
        long now = System.currentTimeMillis();
        int world = client.getWorld();
        sampler = new ReplaySampler();
        locNames.clear();
        Player local = client.getLocalPlayer();
        String rsn = PlayerNames.sanitized(local);
        Path file = dir.resolve(ReplayFileName.fileName(config.replayFileName(), now, ZoneId.systemDefault(), world,
            rsn));
        openedAtMs = now;
        lastModels = 0;
        startCycle = lastCycle;
        stats.reset();
        pitch.reset();
        pitch.setLocNames(id -> locName(client, id));
        pitchPending = false;
        long openStart = System.nanoTime();
        writer.open(file);
        writer.writeDeferred(ReplayLines.hdr(client.getRevision(), world, rsn, now, lastCycle), false);
        // The rest of the start is spread over the next ClientTicks, one piece each (stepStart).
        pluginsDue = true;
        scanner.start();
        stats.addOpenNanos(System.nanoTime() - openStart);
    }

    /** The ClientTick after open: the {@code plugins} line, built here and serialised on the writer's thread. */
    private void writePlugins()
    {
        long start = System.nanoTime();
        pluginsDue = false;
        Supplier<List<PluginEntry>> source = pluginSource;
        List<PluginEntry> plugins = source == null ? null : source.get();
        if (plugins != null)
        {
            writer.writeDeferred(ReplayLines.plugins(lastCycle, plugins), false);
        }
        stats.addOpenNanos(System.nanoTime() - start);
    }

    /**
     * Client thread: a loc's object name for the {@code names} map, the impostor's when the object
     * has impostors (varbit-dependent, as drawn now), cached per id for this file.
     */
    private String locName(Client client, int id)
    {
        if (locNames.containsKey(id))
        {
            return locNames.get(id);
        }
        String name = null;
        ObjectComposition definition = client.getObjectDefinition(id);
        if (definition != null)
        {
            ObjectComposition shown = definition.getImpostorIds() != null ? definition.getImpostor() : null;
            name = shown != null ? shown.getName() : definition.getName();
        }
        locNames.put(id, name);
        return name;
    }

    /**
     * Hands lines to the writer in order. Model lines are big and immutable once built, so they are
     * serialised on the writer's thread; a {@code team} line is already JSON; the rest are serialised
     * at once.
     */
    private void writeAll(List<?> lines)
    {
        for (Object item : lines)
        {
            if (item instanceof String)
            {
                writer.writeJson((String) item);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> line = (Map<String, Object>) item;
            if (ReplayLines.isModelLine(line))
            {
                writer.writeDeferred(line);
            }
            else
            {
                writer.write(line);
            }
        }
    }
}
