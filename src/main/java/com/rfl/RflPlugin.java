package com.rfl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.inject.Inject;
import javax.swing.SwingUtilities;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * RFL local collision plugin. Inside a player-owned house, detects handegg collisions between any
 * two players in view ({@link ContactDetector}) and interceptions ({@link InterceptionDetector}),
 * draws them, and saves them on this computer ({@link CollisionLog}). Nothing is sent anywhere.
 *
 * <p>Owns the wiring between RuneLite events and the pieces that do the work: contact detection
 * each client frame, interception checks each game tick, and the replay recorder
 * ({@link ReplayRecorder}), and the local user's own plugin log ({@link PluginLog}): a snapshot on
 * entering a POH and a line per plugin toggle while logged in. The RFL panel and debug logging
 * live in {@link RflDebug}.
 *
 * <p>Threads: detection, the plugin log and the panel state are client-thread only. The panel is
 * created and removed on the EDT. {@link #inPoh} is a volatile snapshot for readers on other
 * threads.
 */
@Slf4j
@PluginDescriptor(
    name = "RFL Audit"
)
public class RflPlugin extends Plugin
{
    @Inject
    private RflConfig config;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private PohDetector pohDetector;

    @Inject
    private ContactDetector contactDetector;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ContactHighlightOverlay contactHighlightOverlay;

    @Inject
    private HitboxOverlay hitboxOverlay;

    @Inject
    private ContactHighlights contactHighlights;

    @Inject
    private BareBody bareBody;

    @Inject
    private RflDebug debug;

    @Inject
    private CollisionLog collisionLog;

    @Inject
    private ReplayRecorder replayRecorder;

    @Inject
    private PluginSnapshotter pluginSnapshotter;

    @Inject
    private PluginLog pluginLog;

    private final InterceptionDetector interceptionDetector = new InterceptionDetector();

    /** Recomputed each {@link GameTick}; the POH check only needs to run once per tick. */
    private volatile boolean inPoh;

    /** Client thread: whether detection ran last frame, so tracking is reset once when it stops. */
    private boolean wasWatching;

    @Provides
    RflConfig provideConfig(final ConfigManager configManager)
    {
        return configManager.getConfig(RflConfig.class);
    }

    @Override
    protected void startUp()
    {
        overlayManager.add(contactHighlightOverlay);
        overlayManager.add(hitboxOverlay);
        // startUp runs off the client thread, so the bundled kit table is read here, not per frame.
        bareBody.load();
        collisionLog.setListener(replayRecorder::onEvent);
        replayRecorder.setPluginSource(() -> config.logPluginStats() ? pluginSnapshotter.snapshot() : null);
        // The panel shows the plugin list before the first POH visit too; nothing is written here.
        clientThread.invoke(() -> pluginLog.remember(pluginSnapshotter.snapshot()));
        SwingUtilities.invokeLater(debug::syncPanel);
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(contactHighlightOverlay);
        overlayManager.remove(hitboxOverlay);
        // Detection state is client-thread only; a ClientTick may still be running right now.
        // contactDetector.reset() saves any open collisions as ended.
        clientThread.invoke(() ->
        {
            interceptionDetector.reset();
            contactDetector.reset();
            wasWatching = false;
            // So the next start-up inside a POH counts as entering it and takes a plugin snapshot.
            inPoh = false;
            // After the reset, so collisions it saves as ended still reach the open replay.
            replayRecorder.stop();
        });
        SwingUtilities.invokeLater(debug::removePanel);
    }

    @Subscribe
    public void onConfigChanged(final ConfigChanged event)
    {
        if (!RflConfig.GROUP.equals(event.getGroup()))
        {
            return;
        }
        if ("showPanel".equals(event.getKey()))
        {
            SwingUtilities.invokeLater(debug::syncPanel);
        }
    }

    @Subscribe
    public void onGameTick(final GameTick event)
    {
        setInPoh(pohDetector.inPoh(client));
        checkInterceptions();
        replayRecorder.onGameTick(client);
    }

    /** Client thread: see {@link #detects}. */
    private boolean watchingContacts()
    {
        return detects(config.reportContacts(), client.getGameState() == GameState.LOGGED_IN, inPoh);
    }

    /** Contact detection runs logged in, inside a POH, with Detect contacts on. */
    static boolean detects(final boolean detectContacts, final boolean loggedIn, final boolean inPoh)
    {
        return detectContacts && loggedIn && inPoh;
    }

    /**
     * Once per game tick: a player who starts holding a handegg right after a thrown one stopped
     * being drawn, while in contact with someone, intercepted it.
     */
    private void checkInterceptions()
    {
        final boolean logging = config.debugLogging();
        final int tick = client.getTickCount();
        final boolean watching = watchingContacts();
        if (logging)
        {
            debug.logGate(tick, inPoh);
            if (watching)
            {
                debug.logOverlaps(tick);
            }
        }
        if (!watching || !config.detectInterceptions())
        {
            interceptionDetector.reset();
            return;
        }

        boolean ballInFlight = false;
        final List<String> projectiles = new ArrayList<>();
        for (final Projectile projectile : client.getProjectiles())
        {
            projectiles.add(projectile.getId() + "(" + projectile.getRemainingCycles() + ")");
            if (InterceptionDetector.HANDEGG_PROJECTILES.contains(projectile.getId()))
            {
                ballInFlight = true;
            }
        }

        final Map<String, Integer> weapons = contactDetector.weapons(client);
        final Map<String, List<String>> contacts = contactDetector.collidingNow();
        if (logging)
        {
            debug.logInterceptionInputs(tick, projectiles, ballInFlight, weapons, contacts);
        }

        final List<InterceptionDetector.Interception> found = interceptionDetector.onTick(
            tick, ballInFlight, InterceptionDetector.holders(weapons), contacts);
        final String check = interceptionDetector.lastCheck();
        if (check != null)
        {
            debug.record(check);
        }
        if (logging)
        {
            if (check != null)
            {
                log.info("[RFL debug] tick {} {}", tick, check);
            }
            if (!contactDetector.missingMeshes().isEmpty())
            {
                log.info("[RFL debug] tick {} no mesh built for {}", tick, contactDetector.missingMeshes());
            }
            for (final InterceptionDetector.Interception i : found)
            {
                log.info("[RFL debug] tick {} INTERCEPTION receiver={} contacts={}", tick, i.receiver, i.contacts);
            }
        }
        for (final InterceptionDetector.Interception interception : found)
        {
            showInterception(interception);
            saveInterception(interception, tick);
        }
    }

    /** Chat message and receiver tile highlight for one interception, per the settings. */
    private void showInterception(final InterceptionDetector.Interception interception)
    {
        if (config.interceptionChatMessage())
        {
            final String label = ColorUtil.wrapWithColorTag("Interception:", config.interceptionColor());
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                label + " " + interception.receiver + " caught the handegg in contact with "
                    + String.join(", ", interception.contacts), null);
        }
        final Player receiver = contactDetector.players().get(interception.receiver);
        final LocalPoint at = receiver == null ? null : receiver.getLocalLocation();
        if (config.highlightInterceptions() && at != null)
        {
            contactHighlights.addInterception(at.getX(), at.getY(), System.currentTimeMillis());
        }
    }

    /** Appends the interception to the day file (when Save collisions is on), with the receiver's tile. */
    private void saveInterception(final InterceptionDetector.Interception interception, final int tick)
    {
        final Player receiver = contactDetector.players().get(interception.receiver);
        final LocalPoint at = receiver == null ? null : receiver.getLocalLocation();
        final WorldPoint tile = at == null ? null
            : WorldPoint.fromLocalInstance(client, at, client.getTopLevelWorldView().getPlane());
        collisionLog.record(new CollisionLog.Interception(interception.receiver, interception.contacts,
            System.currentTimeMillis(), tick, client.getWorld(),
            tile == null ? -1 : tile.getX(), tile == null ? -1 : tile.getY(), tile == null ? -1 : tile.getPlane()));
    }

    @Subscribe
    public void onClientTick(final ClientTick event)
    {
        debug.refresh(inPoh);
        final boolean watching = watchingContacts();
        if (!watching)
        {
            // Never leave the tracker holding pairs across a period we weren't watching.
            stopTracking();
        }
        // Independent of Detect contacts. Before onFrame, so this frame's collisions carry its cycle;
        // after stopTracking, so collisions it saves as ended still reach a replay that is closing.
        replayRecorder.onClientTick(client, inPoh);
        if (!watching)
        {
            return;
        }
        wasWatching = true;
        contactDetector.onFrame(client);
        debug.recordMissing(contactDetector.missingMeshes());
    }

    @Subscribe
    public void onGameStateChanged(final GameStateChanged event)
    {
        final GameState state = event.getGameState();
        // inPoh is otherwise only recomputed on GameTick; refresh it here so the frames between a
        // scene change and the next tick don't run on the old value (detection and replays both read it).
        if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
        {
            inPoh = false;
            stopTracking();
        }
        else if (state == GameState.LOGGED_IN)
        {
            setInPoh(pohDetector.inPoh(client));
        }
        replayRecorder.onGameStateChanged(state);
    }

    /** Client thread: updates {@link #inPoh} and snapshots the local plugin list on entering a POH. */
    private void setInPoh(final boolean now)
    {
        final boolean was = inPoh;
        inPoh = now;
        if (now && !was)
        {
            final Player local = client.getLocalPlayer();
            pluginLog.snapshot(System.currentTimeMillis(), local == null ? null : sanitizedName(local),
                client.getWorld(), pluginSnapshotter.snapshot());
        }
    }

    /**
     * A plugin of this client was turned on or off. While logged in, logs a toggle line (and a
     * {@code plugin_toggle} replay line) with Log plugin stats on. Only the local client's own
     * plugins ever reach this event. May arrive off the client thread, so the work runs on it.
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
                final Player local = client.getLocalPlayer();
                pluginLog.toggle(System.currentTimeMillis(), local == null ? null : sanitizedName(local),
                    client.getWorld(), name, enabled);
                if (config.logPluginStats())
                {
                    replayRecorder.onPlugins(ReplayRecorder.pluginToggleLine(replayRecorder.cycle(), name, enabled));
                }
            }
            // Keep the panel's list current, including plugins that finished starting after ours.
            pluginLog.remember(pluginSnapshotter.snapshot());
            pluginLog.applyToggle(name, enabled);
        });
    }

    private static String sanitizedName(final Player player)
    {
        final String name = player.getName();
        return name == null ? null : Text.sanitize(name);
    }

    /**
     * RuneLite doesn't call {@link #shutDown} when the client exits, so the open replay is closed
     * here and the exit is held until its gzip trailer is written. ClientShutdown may arrive off the
     * client thread; the recorder's state stays client-thread only, so the stop runs through
     * {@link ClientThread#invoke} (inline when already on it) and the exit waits on the outcome.
     */
    @Subscribe
    public void onClientShutdown(final ClientShutdown event)
    {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        clientThread.invoke(() ->
        {
            try
            {
                replayRecorder.stop().whenComplete((ignored, error) -> done.complete(null));
            }
            catch (RuntimeException e)
            {
                done.complete(null);
                throw e;
            }
        });
        event.waitFor(done);
    }

    /**
     * Client thread. Once per stop (left the POH, logged out, hopped, Detect contacts off): saves
     * open collisions as ended and clears the tracker.
     */
    private void stopTracking()
    {
        if (wasWatching)
        {
            contactDetector.reset();
            wasWatching = false;
        }
    }
}
