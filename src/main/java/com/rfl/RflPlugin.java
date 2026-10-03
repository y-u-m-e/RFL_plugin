package com.rfl;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.swing.SwingUtilities;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;

import okhttp3.OkHttpClient;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * RFL match audit plugin. Reports the local player's own RSN, world, enabled plugins and POH
 * handegg contact events (own contacts, plus nameless collision_seen sightings; never another
 * player's name) to the audit gateway every 10 s while logged in and reporting is enabled.
 *
 * <p>Owns the wiring between RuneLite events and the pieces that do the work: contact detection
 * each client frame ({@link ContactDetector}), interception checks each game tick
 * ({@link InterceptionDetector}), the report heartbeat ({@link ReportSender}), the per-account
 * install ID. Debug panel and debug logging live in {@link RflDebug}.
 *
 * <p>Observer mode overrides reporting: nothing is sent (no reports, no
 * collision_seen), and every handegg collision in view between any two players is saved on this
 * computer only ({@link ObserverLog}). It does not need Enable reporting.
 *
 * <p>Threads: detection, the install ID and the debug state are client-thread only. The debug
 * panel is created and removed on the EDT. {@link #inPoh} is a volatile snapshot for readers on
 * other threads.
 */
@Slf4j
@PluginDescriptor(
    name = "RFL Audit"
)
public class RflPlugin extends Plugin
{
    /** RS-profile config key (group {@code rfl}) holding this account's install ID. */
    static final String INSTALL_ID_KEY = "installId";

    @Inject
    private RflConfig config;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private PluginSnapshotter snapshotter;

    @Inject
    private PohDetector pohDetector;

    @Inject
    private ContactDetector contactDetector;

    @Inject
    private EventQueue eventQueue;

    @Inject
    private ReportSender reportSender;

    @Inject
    private OkHttpClient httpClient;

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
    private ScheduledExecutorService executor;

    @Inject
    private ConfigManager configManager;

    @Inject
    private RflDebug debug;

    @Inject
    private ObserverLog observerLog;

    private final InterceptionDetector interceptionDetector = new InterceptionDetector();

    // Client thread only: the install ID cached for the RS profile it was read for.
    private String installProfileKey;
    private String cachedInstallId;

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
        // The install ID used to be one global item shared by every client on the PC; it is now
        // per RuneScape account (RS profile). Drop the old global value.
        executor.execute(() -> configManager.unsetConfiguration(RflConfig.GROUP, INSTALL_ID_KEY));
        overlayManager.add(contactHighlightOverlay);
        overlayManager.add(hitboxOverlay);
        // startUp runs off the client thread, so the bundled kit table is read here, not per frame.
        bareBody.load();
        reportSender.setEnabled(this::reporting);
        SwingUtilities.invokeLater(debug::syncPanel);
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(contactHighlightOverlay);
        overlayManager.remove(hitboxOverlay);
        // Detection state is client-thread only; a ClientTick may still be running right now.
        // contactDetector.reset() saves any open observer collisions as ended.
        clientThread.invoke(() ->
        {
            interceptionDetector.reset();
            contactDetector.reset();
        });
        SwingUtilities.invokeLater(debug::removePanel);
    }

    /**
     * @param enableReporting the Enable reporting setting
     * @param observerMode    the Observer mode setting
     * @return whether anything may be sent: Observer mode overrides reporting
     */
    public static boolean reportingAllowed(final boolean enableReporting, final boolean observerMode)
    {
        return enableReporting && !observerMode;
    }

    /** Any thread: the send gate for reports, and plugin toggles. */
    private boolean reporting()
    {
        return reportingAllowed(config.enableReporting(), config.observerMode());
    }

    @Subscribe
    public void onConfigChanged(final ConfigChanged event)
    {
        if (!RflConfig.GROUP.equals(event.getGroup()))
        {
            return;
        }
        if ("showDebugPanel".equals(event.getKey()))
        {
            SwingUtilities.invokeLater(debug::syncPanel);
        }
    }

    @Subscribe
    public void onGameTick(final GameTick event)
    {
        inPoh = pohDetector.inPoh(client);
        checkInterceptions();
    }

    /**
     * Client thread. This RuneScape account's install ID (RS-profile config), generated the first
     * time the account has none, so two clients on one PC never share an ID.
     *
     * @return the install ID, or null while no RS profile is loaded (logged out)
     */
    private String currentInstallId()
    {
        final String key = configManager.getRSProfileKey();
        if (key == null || !key.equals(installProfileKey))
        {
            installProfileKey = key;
            cachedInstallId = installIdFor(key,
                () -> configManager.getRSProfileConfiguration(RflConfig.GROUP, INSTALL_ID_KEY),
                id -> configManager.setRSProfileConfiguration(RflConfig.GROUP, INSTALL_ID_KEY, id));
        }
        return cachedInstallId;
    }

    /**
     * @param profileKey current RS profile key, or null when none is loaded
     * @param read       reads the stored ID for that profile
     * @param write      stores a new ID for that profile
     * @return the stored ID, a freshly stored UUID when there was none, or null without a profile
     */
    static String installIdFor(final String profileKey, final Supplier<String> read, final Consumer<String> write)
    {
        if (profileKey == null)
        {
            return null;
        }
        final String stored = read.get();
        if (stored != null && !stored.isEmpty())
        {
            return stored;
        }
        final String fresh = UUID.randomUUID().toString();
        write.accept(fresh);
        return fresh;
    }

    /** Client thread: see {@link #detects}. */
    private boolean watchingContacts()
    {
        return detects(config.enableReporting(), config.reportContacts(), config.observerMode(),
            client.getGameState() == GameState.LOGGED_IN, inPoh);
    }

    /**
     * Contact detection runs logged in, inside a POH, with either reporting and Detect contacts on
     * or Observer mode on (which needs neither).
     */
    static boolean detects(final boolean enableReporting, final boolean reportContacts, final boolean observerMode,
        final boolean loggedIn, final boolean inPoh)
    {
        return (observerMode || enableReporting && reportContacts) && loggedIn && inPoh;
    }

    /**
     * Once per game tick: a player who starts holding a handegg right after a thrown one stopped
     * being drawn, while in contact with someone, intercepted it. Local display only for now.
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

    @Subscribe
    public void onClientTick(final ClientTick event)
    {
        debug.refresh(inPoh);
        if (!watchingContacts())
        {
            // Never leave the tracker holding pairs across a period we weren't watching.
            closeOrResetTracking(config.enableReporting());
            return;
        }
        for (final RflEvent contactEvent : contactDetector.onFrame(client))
        {
            // Observer mode starts nothing reportable; the only events then are contact_ends for
            // self contacts open when it went on, queued (not sent) so each start keeps its end.
            debug.recordEvent(contactEvent);
            if (config.enableReporting())
            {
                eventQueue.add(contactEvent);
            }
        }
        debug.recordMissing(contactDetector.missingMeshes());
    }

    @Subscribe
    public void onGameStateChanged(final GameStateChanged event)
    {
        final GameState state = event.getGameState();
        if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
        {
            closeOrResetTracking(config.enableReporting());
        }
    }

    /**
     * Stops contact tracking for a period we're no longer watching (left the POH, logged out,
     * hopped, or reporting turned off). Closes any open pairs with a real {@code contact_end}
     * when there's still somewhere to send it (reporting on); otherwise there's nothing to queue
     * so it just clears the tracker.
     *
     * @param queueEnds true to queue contact_end events for open pairs; false to silently reset
     */
    private void closeOrResetTracking(final boolean queueEnds)
    {
        if (queueEnds)
        {
            for (final RflEvent endEvent : contactDetector.endAll(client))
            {
                debug.recordEvent(endEvent);
                eventQueue.add(endEvent);
            }
        }
        else
        {
            contactDetector.reset();
        }
    }

    @Subscribe
    public void onPluginChanged(final PluginChanged event)
    {
        if (!reporting() || !config.reportPlugins())
        {
            return;
        }

        // PluginChanged can fire off the client thread (e.g. toggled from the sidebar on the
        // Swing EDT); capture the timestamp now, then hop onto the client thread before reading
        // client.getTickCount().
        final long now = System.currentTimeMillis();
        clientThread.invokeLater(() -> eventQueue.add(snapshotter.toggleEvent(event, now, client.getTickCount())));
    }

    /**
     * Sends one report batch every 10 s while logged in and reporting is enabled: the heartbeat
     * that also carries whatever contact/toggle events queued up since the last send. The report
     * is built and the queue drained on the client thread, so events and state stay consistent;
     * the JSON encoding and the send run on OkHttp's dispatcher executor.
     */
    @Schedule(period = 10, unit = ChronoUnit.SECONDS)
    public void sendReport()
    {
        if (!reporting())
        {
            return;
        }

        clientThread.invoke(() ->
        {
            if (client.getGameState() != GameState.LOGGED_IN)
            {
                return;
            }

            final Player localPlayer = client.getLocalPlayer();
            final String name = localPlayer == null ? null : localPlayer.getName();
            final String rsn = name == null ? null : Text.sanitize(name);
            if (rsn == null)
            {
                return;
            }

            final String id = currentInstallId();
            if (id == null)
            {
                return;
            }

            final List<RflEvent> drained = eventQueue.drain(EventQueue.MAX_BATCH);
            final RflReport report = new RflReport(
                rsn,
                id,
                client.getWorld(),
                System.currentTimeMillis(),
                inPoh,
                "",
                snapshotter.snapshot(),
                drained,
                new RflReport.Features(config.reportPlugins(), config.reportContacts()));

            httpClient.dispatcher().executorService().execute(() -> reportSender.send(report, drained));
        });
    }
}
