package com.rfl;

import sh.yumekui.toolkit.text.PlayerNames;
import com.rfl.contact.BareBody;
import com.rfl.contact.ContactDetector;
import com.rfl.incomplete.IncompleteReporter;
import com.rfl.log.CollisionLog;
import com.rfl.log.PluginAudit;
import com.rfl.log.PluginSnapshotter;
import com.rfl.overlay.RflOverlays;
import com.rfl.panel.RflPanelController;
import com.rfl.panel.SessionEvents;
import com.rfl.replay.ReplayLines;
import com.rfl.replay.ReplayRecorder;
import com.rfl.teams.TeamMenu;
import com.rfl.teams.Teams;
import sh.yumekui.toolkit.scene.PohDetector;
import sh.yumekui.toolkit.scene.SceneStamps;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.swing.SwingUtilities;

import com.google.inject.Provides;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * RFL Audit: league tools for RuneScape Football League games played in a player-owned house.
 * Everything stays on this computer; nothing is sent anywhere.
 *
 * <p>This class is wiring only: each RuneLite event goes to the component that does the work.
 * Contacts are detected each frame ({@link ContactDetector}) and incompletes ruled from them
 * ({@link IncompleteReporter}); both are saved locally ({@link CollisionLog}); house visits can be
 * recorded as replays ({@link ReplayRecorder}); the local user's own plugin list is logged on
 * entering and leaving a house ({@link PluginAudit}); players are put on teams from the panel or
 * the right-click menu ({@link Teams}, {@link TeamMenu}); and the sidebar panel shows it all
 * ({@link RflPanelController}).
 *
 * <p>Threads: every handler runs on the client thread except where noted. {@link #inPoh} is a
 * volatile snapshot for readers on other threads.
 */
@PluginDescriptor(
    name = "RFL Audit"
)
public class RflPlugin extends Plugin
{
    /**
     * Longest the client's exit waits for the replay's gzip trailer. A finished file needs well
     * under a second; this only bounds a wedged disk.
     */
    private static final long EXIT_WAIT_SECONDS = 5;

    @Inject
    private RflConfig config;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private RflOverlays overlays;

    @Inject
    private PohDetector pohDetector;

    @Inject
    private SceneStamps sceneStamps;

    @Inject
    private ContactDetector contactDetector;

    @Inject
    private BareBody bareBody;

    @Inject
    private IncompleteReporter incompleteReporter;

    @Inject
    private CollisionLog collisionLog;

    @Inject
    private SessionEvents sessionEvents;

    @Inject
    private ReplayRecorder replayRecorder;

    @Inject
    private PluginSnapshotter pluginSnapshotter;

    @Inject
    private PluginAudit pluginAudit;

    @Inject
    private Teams teams;

    @Inject
    private TeamMenu teamMenu;

    @Inject
    private RflPanelController panel;

    /** Recomputed each {@link GameTick}; the POH check only needs to run once per tick. */
    private volatile boolean inPoh;

    @Provides
    RflConfig provideConfig(final ConfigManager configManager)
    {
        return configManager.getConfig(RflConfig.class);
    }

    @Override
    protected void startUp()
    {
        overlays.addAll();
        // startUp runs off the client thread, so the bundled kit table is read here, not per frame.
        bareBody.load();
        // Session counts reset on every start-up.
        sessionEvents.clear();
        collisionLog.setListener(this::onCollisionOrIncomplete);
        // First-use costs (class loading, the colour table, Gson adapters) off the client thread.
        executor.execute(replayRecorder::warmUp);
        replayRecorder.setPluginSource(pluginSnapshotter::snapshot);
        // Team assignments survive restarts; a change reaches an open replay as a team line.
        teams.load();
        replayRecorder.setTeamSource(teams::team);
        teams.setListener(new Teams.Listener()
        {
            @Override
            public void changed(String name)
            {
                clientThread.invoke(() -> replayRecorder.onTeamChanged(name));
                panel.invalidate();
            }

            @Override
            public void cleared()
            {
                clientThread.invoke(replayRecorder::onTeamsCleared);
                panel.invalidate();
            }
        });
        SwingUtilities.invokeLater(panel::syncPanel);
    }

    @Override
    protected void shutDown()
    {
        overlays.removeAll();
        teams.setListener(null);
        // Detection state is client-thread only; a ClientTick may still be running right now.
        clientThread.invoke(() ->
        {
            incompleteReporter.reset();
            // Saves any open collisions as ended.
            contactDetector.reset();
            // So the next start-up inside a POH counts as entering it and takes a plugin snapshot.
            inPoh = false;
            // After the reset, so collisions it saves as ended still reach the open replay.
            replayRecorder.stopAndReleaseWriter();
        });
        SwingUtilities.invokeLater(panel::removePanel);
    }

    /**
     * Caller's thread (the client thread): a saved collision or incomplete goes to an open replay
     * and the panel, stamped with the scene it happened in so the panel only highlights its tile there.
     */
    private void onCollisionOrIncomplete(Object event)
    {
        replayRecorder.onEvent(event);
        final WorldView view = client.getTopLevelWorldView();
        sessionEvents.onEvent(event, view == null ? SceneStamps.NONE : sceneStamps.stamp(view.getPlane()));
    }

    @Subscribe
    public void onConfigChanged(final ConfigChanged event)
    {
        if (RflConfig.GROUP.equals(event.getGroup()) && "showPanel".equals(event.getKey()))
        {
            SwingUtilities.invokeLater(panel::syncPanel);
        }
    }

    @Subscribe
    public void onGameTick(final GameTick event)
    {
        setInPoh(pohDetector.inPoh(client));
        incompleteReporter.onGameTick(detecting());
        replayRecorder.onGameTick(client);
    }

    @Subscribe
    public void onClientTick(final ClientTick event)
    {
        panel.refresh(inPoh);
        final boolean detecting = detecting();
        if (!detecting)
        {
            // Never leave the tracker holding pairs across a period we weren't watching.
            contactDetector.stopTracking();
        }
        // Independent of Detect contacts. Before the contact update, so this frame's collisions
        // carry its cycle; after stopTracking, so collisions it saves still reach a closing replay.
        replayRecorder.onClientTick(client, inPoh);
        if (detecting)
        {
            contactDetector.onFrame(client);
            incompleteReporter.onFrame();
        }
    }

    /** Contact detection runs logged in, inside a POH, with Detect contacts on. */
    private boolean detecting()
    {
        return ContactDetector.detects(config.reportContacts(), client.getGameState() == GameState.LOGGED_IN, inPoh);
    }

    /**
     * With Record overhead chat on, public chat over a player's head (the local player's too) goes
     * into an open replay as a {@code chat} line, which refs use for keeping time. NPC overhead
     * text is ignored.
     */
    @Subscribe
    public void onOverheadTextChanged(final OverheadTextChanged event)
    {
        if (!config.recordOverheadChat() || !(event.getActor() instanceof Player))
        {
            return;
        }
        replayRecorder.onOverheadText(client.getGameCycle(), PlayerNames.sanitized((Player) event.getActor()),
            event.getOverheadText());
    }

    @Subscribe
    public void onMenuEntryAdded(final MenuEntryAdded event)
    {
        teamMenu.onMenuEntryAdded(event, inPoh);
    }

    @Subscribe
    public void onGameStateChanged(final GameStateChanged event)
    {
        final GameState state = event.getGameState();
        if (state == GameState.LOADING || state == GameState.HOPPING || state == GameState.LOGIN_SCREEN)
        {
            // A new scene: recorded scene tiles from before no longer point at the same place.
            sceneStamps.bump();
        }
        // inPoh is otherwise only recomputed on GameTick; refresh it here so the frames between a
        // scene change and the next tick don't run on the old value (detection and replays both read it).
        if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
        {
            setInPoh(false);
            contactDetector.stopTracking();
        }
        else if (state == GameState.LOGGED_IN)
        {
            setInPoh(pohDetector.inPoh(client));
        }
        replayRecorder.onGameStateChanged(state);
    }

    /** Updates {@link #inPoh}; entering or leaving a house snapshots the local plugin list. */
    private void setInPoh(final boolean now)
    {
        final boolean was = inPoh;
        inPoh = now;
        if (now != was)
        {
            pluginAudit.onPohChanged(now);
        }
    }

    /**
     * A plugin of this client was turned on or off. While logged in, logs a toggle line and a
     * {@code plugin_toggle} replay line. Only the local client's own plugins ever reach this event.
     * May arrive off the client thread, so the work runs on it.
     */
    @Subscribe
    public void onPluginChanged(final PluginChanged event)
    {
        final String name = PluginSnapshotter.displayName(event.getPlugin());
        final boolean enabled = event.isLoaded();
        clientThread.invoke(() ->
        {
            if (client.getGameState() == GameState.LOGGED_IN)
            {
                pluginAudit.onPluginToggled(name, enabled);
                replayRecorder.onPlugins(ReplayLines.pluginToggle(replayRecorder.cycle(), name, enabled));
            }
        });
    }

    /**
     * RuneLite doesn't call {@link #shutDown} when the client exits, so the open replay is closed
     * here and the exit is held until its gzip trailer is written, for at most
     * {@link #EXIT_WAIT_SECONDS}. ClientShutdown may arrive off the client thread; the recorder's
     * state stays client-thread only, so the stop runs through {@link ClientThread#invoke} (inline
     * when already on it) and the exit waits on the outcome.
     */
    @Subscribe
    public void onClientShutdown(final ClientShutdown event)
    {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        clientThread.invoke(() ->
        {
            try
            {
                replayRecorder.shutdown().whenComplete((ignored, error) -> done.complete(null));
            }
            catch (RuntimeException e)
            {
                done.complete(null);
                throw e;
            }
        });
        event.waitFor(done.completeOnTimeout(null, EXIT_WAIT_SECONDS, TimeUnit.SECONDS));
    }
}
