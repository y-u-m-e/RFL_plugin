package com.rfl.panel;

import sh.yumekui.toolkit.clipboard.Clipboard;
import sh.yumekui.toolkit.text.PlayerNames;
import com.rfl.RflConfig;
import com.rfl.log.CollisionLog;
import com.rfl.log.PluginHistory;
import com.rfl.log.PluginLog;
import com.rfl.overlay.EventTileOverlay;
import com.rfl.overlay.TeamTileOverlay;
import com.rfl.replay.ReplayRecorder;
import com.rfl.replay.ReplayState;
import com.rfl.replay.ReplayStatus;
import com.rfl.teams.Teams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.LinkBrowser;

/**
 * The RFL sidebar panel behind "Show RFL panel": adds and removes it, builds a {@link PanelModel}
 * on the client thread at most every {@link #REFRESH_MS} (sooner when an event arrives) and hands
 * it to the EDT only when it changed, and runs the Copy plugin history, Clear and Open folder buttons.
 *
 * <p>Threads: {@link #syncPanel}, {@link #removePanel} and the button handlers on the EDT;
 * {@link #refresh} on the client thread; file IO on the injected executor.
 */
@Slf4j
@Singleton
public final class RflPanelController
{
    static final long REFRESH_MS = 500;
    /** Where the RFL button sits in the sidebar; RuneLite orders buttons by this, lowest first. */
    private static final int NAVIGATION_PRIORITY = 10;
    /** While a replay is saving, so its progress bar moves. */
    static final long SAVING_REFRESH_MS = 200;

    private final RflConfig config;
    private final ClientToolbar clientToolbar;
    private final SessionEvents session;
    private final PluginLog pluginLog;
    private final CollisionLog collisionLog;
    private final ReplayRecorder replayRecorder;
    private final ScheduledExecutorService executor;
    private final ConfigManager configManager;
    private final Gson gson;
    private final Teams teams;
    private final Client client;
    private final TeamTileOverlay teamTiles;
    private final EventTileOverlay eventTiles;

    // EDT only.
    private RflPanel panel;
    private NavigationButton button;

    // Client thread only.
    private long refreshAt;
    private long seenSessionVersion = -1;
    private long seenTeamsVersion = -1;
    private boolean seenInPoh;
    private boolean seenRecording;
    private boolean seenArmed;
    private ReplayState seenReplayState = ReplayState.IDLE;
    private PanelModel lastModel;
    /**
     * Set from any thread (a new panel on the EDT, a team change) so the next refresh sends a model
     * unconditionally. Read and cleared in one step, so a set racing the refresh is never lost.
     */
    private final AtomicBoolean resend = new AtomicBoolean();

    @Inject
    RflPanelController(RflConfig config, ClientToolbar clientToolbar, SessionEvents session,
        PluginLog pluginLog, CollisionLog collisionLog, ReplayRecorder replayRecorder,
        ScheduledExecutorService executor, ConfigManager configManager, Gson gson, Teams teams, Client client,
        TeamTileOverlay teamTiles, EventTileOverlay eventTiles)
    {
        this.eventTiles = eventTiles;
        this.teamTiles = teamTiles;
        this.teams = teams;
        this.client = client;
        this.gson = gson;
        this.collisionLog = collisionLog;
        this.configManager = configManager;
        this.config = config;
        this.clientToolbar = clientToolbar;
        this.session = session;
        this.pluginLog = pluginLog;
        this.replayRecorder = replayRecorder;
        this.executor = executor;
    }

    /** {@code RUNELITE_DIR/rfl}, the folder Open folder shows. */
    static Path rflDir()
    {
        return RuneLite.RUNELITE_DIR.toPath().resolve("rfl");
    }

    /** EDT: adds or removes the RFL panel to match the setting. */
    public void syncPanel()
    {
        if (!config.showPanel())
        {
            removePanel();
            return;
        }
        if (button != null)
        {
            return;
        }
        panel = new RflPanel(this::openFolder, this::copyPluginHistory, this::toggleRecording, this::clearCollisions,
            this::clearIncompletes, teams::assign, teams::clear);
        panel.setTeamsShowing(teamTiles::setShowing);
        panel.setEventTile(eventTiles::select);
        button = NavigationButton.builder()
            .tooltip("RFL")
            .icon(RflPanel.icon())
            .priority(NAVIGATION_PRIORITY)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(button);
        // A new panel starts empty: make the next client-thread refresh send a model.
        invalidate();
    }

    /** EDT. */
    public void removePanel()
    {
        if (button != null)
        {
            clientToolbar.removeNavigation(button);
        }
        button = null;
        panel = null;
        teamTiles.setShowing(false);
        eventTiles.select(null);
    }

    /** Any thread: the next {@link #refresh} rebuilds and re-sends the model. */
    public void invalidate()
    {
        resend.set(true);
    }

    /**
     * Client thread, every ClientTick: rebuilds the model when something it shows changed, or at
     * most every {@link #REFRESH_MS} for the rest ({@link #SAVING_REFRESH_MS} while a replay is
     * saving), and sends it to the EDT only when it differs. Runs whether or not a file is open, so
     * a save finishing on the writer's thread after the recorder stopped keeps showing.
     */
    public void refresh(boolean inPoh)
    {
        if (!config.showPanel())
        {
            return;
        }
        long now = System.currentTimeMillis();
        long sessionVersion = session.version();
        boolean recording = replayRecorder.recording();
        boolean armed = config.recordReplays();
        // The cheap state first: the full status is only built once the throttle lets a refresh through.
        ReplayState replayState = replayRecorder.state();
        boolean force = resend.getAndSet(false);
        long teamsVersion = teams.version();
        boolean changed = force || sessionVersion != seenSessionVersion || teamsVersion != seenTeamsVersion
            || inPoh != seenInPoh || recording != seenRecording || armed != seenArmed
            || replayState != seenReplayState;
        long every = replayState == ReplayState.SAVING ? SAVING_REFRESH_MS : REFRESH_MS;
        if (!changed && now - refreshAt < every)
        {
            return;
        }
        ReplayStatus replay = replayRecorder.status(now);
        seenReplayState = replay.getState();
        boolean buttonChanged = force || armed != seenArmed || recording != seenRecording;
        refreshAt = now;
        seenSessionVersion = sessionVersion;
        seenTeamsVersion = teamsVersion;
        seenInPoh = inPoh;
        seenRecording = recording;
        if (buttonChanged)
        {
            SwingUtilities.invokeLater(() ->
            {
                if (panel != null)
                {
                    panel.setRecording(armed, recording);
                }
            });
        }
        seenArmed = armed;

        PanelModel model = PanelModel.of(inPoh, recording, session.collisionCount(), session.incompleteCount(),
            session.collisions(), session.incompletes(), session.latest(), PanelModel.replayStrip(replay, armed, now),
            PanelModel.savedTick(collisionLog.lastSavedAtMs(), pluginLog.lastSavedAtMs(), now),
            PanelModel.teams(inPoh ? playersHere() : List.of(), teams.assigned(), config.reportContacts()),
            session::stamp, ZoneId.systemDefault());
        if (!force && model.equals(lastModel))
        {
            return;
        }
        lastModel = model;
        SwingUtilities.invokeLater(() ->
        {
            if (panel != null)
            {
                panel.update(model);
            }
        });
    }

    /**
     * EDT: flips the Record replays setting. The recorder opens or closes the file on its next
     * ClientTick (closing saves it), and the next refresh relabels the button.
     */
    private void toggleRecording()
    {
        configManager.setConfiguration(RflConfig.GROUP, "recordReplays", !config.recordReplays());
        invalidate();
    }

    /** Client thread: sanitized names of the players in the scene, the local player included. */
    private List<String> playersHere()
    {
        List<String> names = new ArrayList<>();
        WorldView view = client.getTopLevelWorldView();
        if (view == null)
        {
            return names;
        }
        for (Player p : view.players())
        {
            String name = PlayerNames.sanitized(p);
            if (name != null)
            {
                names.add(name);
            }
        }
        return names;
    }

    /** EDT, after the panel's inline confirmation: clears this session's collisions (never the files). */
    private void clearCollisions()
    {
        session.clearCollisions();
        invalidate();
    }

    /** EDT, after the panel's inline confirmation: clears this session's incompletes (never the files). */
    private void clearIncompletes()
    {
        session.clearIncompletes();
        invalidate();
    }

    /** EDT: creates {@code RUNELITE_DIR/rfl} off the EDT, then opens it as a plain path. */
    private void openFolder()
    {
        executor.execute(() ->
        {
            Path dir = rflDir();
            try
            {
                Files.createDirectories(dir);
            }
            catch (IOException e)
            {
                log.warn("RFL: can't create {}", dir, e);
            }
            // A plain path: LinkBrowser treats a file: URI string as a missing file.
            LinkBrowser.open(dir.toAbsolutePath().toString());
        });
    }

    /**
     * EDT: reads today's plugin file and lays it out for Discord ({@link PluginHistory}) off the EDT,
     * then copies the text on the EDT and says what was copied.
     */
    private void copyPluginHistory()
    {
        long now = System.currentTimeMillis();
        executor.execute(() ->
        {
            String raw;
            try
            {
                raw = pluginLog.readToday(now);
            }
            catch (IOException e)
            {
                log.warn("RFL: can't read today's plugin file", e);
                showCopyResult(PanelModel.COPY_FAILED);
                return;
            }
            PluginHistory.Result history = raw == null ? null
                : PluginHistory.format(gson, Arrays.asList(raw.split("\r?\n")), ZoneId.systemDefault(),
                    PluginHistory.DISCORD_LIMIT);
            String text = history == null ? null : history.getText();
            SwingUtilities.invokeLater(() ->
            {
                String message = text == null ? PanelModel.NO_HISTORY : PluginHistory.copiedMessage(history);
                if (text != null && !Clipboard.copy(text))
                {
                    message = PanelModel.CLIPBOARD_BUSY;
                }
                if (panel != null)
                {
                    panel.showCopyResult(message);
                }
            });
        });
    }

    private void showCopyResult(String message)
    {
        SwingUtilities.invokeLater(() ->
        {
            if (panel != null)
            {
                panel.showCopyResult(message);
            }
        });
    }
}
