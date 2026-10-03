package com.rfl;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.concurrent.ScheduledExecutorService;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.LinkBrowser;

/**
 * The RFL sidebar panel behind "Show RFL panel": adds and removes it, builds a {@link PanelModel}
 * on the client thread at most every {@link #REFRESH_MS} (sooner when an event arrives) and hands
 * it to the EDT only when it changed, and runs the Copy plugin history and Open folder buttons.
 *
 * <p>Threads: {@link #syncPanel}, {@link #removePanel} and the button handlers on the EDT;
 * {@link #refresh} on the client thread; file IO on the injected executor.
 */
@Slf4j
@Singleton
final class RflPanelController
{
    static final long REFRESH_MS = 500;

    private final RflConfig config;
    private final ClientToolbar clientToolbar;
    private final RflDebug debug;
    private final SessionEvents session;
    private final PluginLog pluginLog;
    private final ReplayRecorder replayRecorder;
    private final ScheduledExecutorService executor;
    private final ConfigManager configManager;

    // EDT only.
    private RflPanel panel;
    private NavigationButton button;

    // Client thread only.
    private long refreshAt;
    private long seenSessionVersion = -1;
    private boolean seenInPoh;
    private boolean seenRecording;
    private boolean seenArmed;
    private PanelModel lastModel;
    /** Set on the EDT when a new panel is created, so the next refresh sends a model unconditionally. */
    private volatile boolean resend;

    @Inject
    RflPanelController(RflConfig config, ClientToolbar clientToolbar, RflDebug debug, SessionEvents session,
        PluginLog pluginLog, ReplayRecorder replayRecorder, ScheduledExecutorService executor,
        ConfigManager configManager)
    {
        this.configManager = configManager;
        this.config = config;
        this.clientToolbar = clientToolbar;
        this.debug = debug;
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
    void syncPanel()
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
        panel = new RflPanel(this::openFolder, this::copyPluginHistory, this::toggleRecording);
        button = NavigationButton.builder()
            .tooltip("RFL")
            .icon(RflPanel.icon())
            .priority(10)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(button);
        // A new panel starts empty: make the next client-thread refresh send a model.
        invalidate();
    }

    /** EDT. */
    void removePanel()
    {
        if (button != null)
        {
            clientToolbar.removeNavigation(button);
        }
        button = null;
        panel = null;
    }

    /** Any thread: the next {@link #refresh} rebuilds and re-sends the model. */
    void invalidate()
    {
        resend = true;
    }

    /**
     * Client thread, every ClientTick: rebuilds the model when something it shows changed, or at
     * most every {@link #REFRESH_MS} for the rest, and sends it to the EDT only when it differs.
     */
    void refresh(boolean inPoh)
    {
        if (!config.showPanel())
        {
            return;
        }
        long now = System.currentTimeMillis();
        long sessionVersion = session.version();
        boolean recording = replayRecorder.recording();
        boolean armed = config.recordReplays();
        boolean force = resend;
        resend = false;
        boolean changed = force || sessionVersion != seenSessionVersion
            || inPoh != seenInPoh || recording != seenRecording || armed != seenArmed;
        if (!changed && now - refreshAt < REFRESH_MS)
        {
            return;
        }
        boolean buttonChanged = force || armed != seenArmed || recording != seenRecording;
        refreshAt = now;
        seenSessionVersion = sessionVersion;
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
            session.collisions(), session.incompletes(), session.latest(),
            config.debugLogging() ? debug.text(inPoh) : null, ZoneId.systemDefault());
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

    /** EDT: reads today's plugin file off the EDT, then copies it verbatim on the EDT. */
    private void copyPluginHistory()
    {
        long now = System.currentTimeMillis();
        executor.execute(() ->
        {
            String text;
            try
            {
                text = pluginLog.readToday(now);
            }
            catch (IOException e)
            {
                log.warn("RFL: can't read today's plugin file", e);
                showCopyResult(PanelModel.COPY_FAILED);
                return;
            }
            int lines = PanelModel.lineCount(text);
            SwingUtilities.invokeLater(() ->
            {
                String message = PanelModel.copyResult(lines);
                if (lines > 0)
                {
                    try
                    {
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
                    }
                    catch (IllegalStateException e)
                    {
                        log.warn("RFL: clipboard unavailable", e);
                        message = "Clipboard busy, try again";
                    }
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
