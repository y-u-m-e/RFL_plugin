package com.rfl;

import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.swing.SwingUtilities;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;

import okhttp3.OkHttpClient;

import com.rfl.game.GameClient;
import com.rfl.game.GamePanel;
import com.rfl.game.GameSession;

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
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * RFL match audit plugin. Reports enabled plugins, RSN, world, nearby players and POH contact
 * events to the audit gateway every 10 s while logged in and reporting is enabled (spec §3).
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
    private ClientToolbar clientToolbar;

    @Inject
    private GameSession gameSession;

    @Inject
    private GameClient gameClient;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private ConfigManager configManager;

    /** RS-profile config key (group {@code rfl}) holding this account's install ID. */
    static final String INSTALL_ID_KEY = "installId";

    // Client thread only: the install ID cached for the RS profile it was read for.
    private String installProfileKey;
    private String cachedInstallId;

    /**
     * RSN/install id/world for {@link GameClient}, refreshed on the client thread each game tick
     * so the game client (OkHttp/EDT threads) never reads {@link Client} itself. Null while logged out.
     */
    private volatile GameClient.Identity identity;

    // "RFL" game panel: created/added/removed on the EDT; polling stop is thread-safe.
    private volatile GamePanel gamePanel;
    private NavigationButton gameButton;

    private static final int DEBUG_EVENTS = 30;
    private static final long DEBUG_REFRESH_MS = 600;

    // Debug panel: the panel and its button are touched on the Swing EDT only; the event list and
    // refresh timer on the client thread only.
    private DebugPanel debugPanel;
    private NavigationButton debugButton;
    private final Deque<String> debugEvents = new ArrayDeque<>();
    private long debugRefreshAt;
    private List<String> lastMissing = Collections.emptyList();

    private final InterceptionDetector interceptionDetector = new InterceptionDetector();

    // Debug logging state: only log what changed, so the log stays readable.
    private String lastGateLog = "";
    private String lastProjectileLog = "";
    private Map<String, Integer> lastWeapons = Collections.emptyMap();
    private String lastContactLog = "";

    /**
     * Cached each {@link GameTick}; {@link ClientTick} reads it rather than recomputing per
     * frame since {@link PohDetector} only needs to run once per game tick.
     */
    private volatile boolean inPoh;

    /**
     * Provides the plugin configuration through RuneLite's config manager.
     *
     * @param configManager central RuneLite config manager
     * @return plugin config instance
     */
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
        gameClient.setIdentitySupplier(() -> identity);
        SwingUtilities.invokeLater(this::syncDebugPanel);
        SwingUtilities.invokeLater(this::addGamePanel);
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(contactHighlightOverlay);
        overlayManager.remove(hitboxOverlay);
        // Detection state is client-thread only; a ClientTick may still be running right now.
        clientThread.invoke(() ->
        {
            interceptionDetector.reset();
            contactDetector.reset();
        });
        SwingUtilities.invokeLater(this::removeDebugPanel);
        final GamePanel panel = gamePanel;
        if (panel != null)
        {
            panel.dispose();
        }
        SwingUtilities.invokeLater(this::removeGamePanel);
    }

    /** EDT. The "RFL" panel is present whenever the plugin runs; it polls only with reporting on. */
    private void addGamePanel()
    {
        if (gameButton != null)
        {
            return;
        }
        final GamePanel panel = new GamePanel(gameClient, gameSession, () -> identity, executor);
        gameButton = NavigationButton.builder()
            .tooltip("RFL")
            .icon(DebugPanel.icon())
            .priority(9)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(gameButton);
        gamePanel = panel;
        panel.setReporting(config.enableReporting());
    }

    /** EDT. */
    private void removeGamePanel()
    {
        if (gamePanel != null)
        {
            gamePanel.dispose();
        }
        if (gameButton != null)
        {
            clientToolbar.removeNavigation(gameButton);
        }
        gameButton = null;
        gamePanel = null;
    }

    @Subscribe
    public void onConfigChanged(final ConfigChanged event)
    {
        if ("rfl".equals(event.getGroup()) && "showDebugPanel".equals(event.getKey()))
        {
            SwingUtilities.invokeLater(this::syncDebugPanel);
        }
        if ("rfl".equals(event.getGroup()) && "enableReporting".equals(event.getKey()))
        {
            SwingUtilities.invokeLater(() ->
            {
                if (gamePanel != null)
                {
                    gamePanel.setReporting(config.enableReporting());
                }
            });
        }
    }

    /** EDT: adds or removes the debug panel to match the setting. */
    private void syncDebugPanel()
    {
        if (!config.showDebugPanel())
        {
            removeDebugPanel();
            return;
        }
        if (debugButton != null)
        {
            return;
        }
        debugPanel = new DebugPanel();
        debugButton = NavigationButton.builder()
            .tooltip("RFL Debug")
            .icon(DebugPanel.icon())
            .priority(10)
            .panel(debugPanel)
            .build();
        clientToolbar.addNavigation(debugButton);
    }

    /** EDT. */
    private void removeDebugPanel()
    {
        if (debugButton != null)
        {
            clientToolbar.removeNavigation(debugButton);
        }
        debugButton = null;
        debugPanel = null;
    }

    /** Client thread: adds a line to the debug panel's recent events (newest first). */
    private void recordDebug(final String line)
    {
        if (!config.showDebugPanel())
        {
            return;
        }
        debugEvents.addFirst("tick " + client.getTickCount() + " " + line);
        while (debugEvents.size() > DEBUG_EVENTS)
        {
            debugEvents.removeLast();
        }
    }

    private void recordDebugEvent(final RflEvent event)
    {
        recordDebug(event.type + " " + event.a + " ~ " + event.b + " depth " + event.depth);
    }

    /** Client thread: about every 600 ms, snapshots detection state as text and hands it to the EDT. */
    private void refreshDebugPanel()
    {
        final long now = System.currentTimeMillis();
        if (!config.showDebugPanel() || now - debugRefreshAt < DEBUG_REFRESH_MS)
        {
            return;
        }
        debugRefreshAt = now;
        final String text = debugText();
        SwingUtilities.invokeLater(() ->
        {
            if (debugPanel != null)
            {
                debugPanel.show(text);
            }
        });
    }

    private String debugText()
    {
        final String source = config.hitboxSource() == RflConfig.HitboxSource.BARE_BODY ? "bare" : "equipped";
        final StringBuilder sb = new StringBuilder();
        sb.append("GATE\n")
            .append("logged in: ").append(yesNo(client.getGameState() == GameState.LOGGED_IN)).append('\n')
            .append("reporting: ").append(yesNo(config.enableReporting())).append('\n')
            .append("in POH: ").append(yesNo(inPoh)).append('\n')
            .append("detect contacts: ").append(yesNo(config.reportContacts())).append('\n')
            .append("hitbox source: ").append(source).append("\n\n");

        sb.append("PLAYERS IN VIEW\n");
        final Map<String, PosedMesh> meshes = new TreeMap<>(contactDetector.meshes());
        final List<String> missing = contactDetector.missingMeshes();
        if (meshes.isEmpty() && missing.isEmpty())
        {
            sb.append("none (or detection not running)\n");
        }
        for (final Map.Entry<String, PosedMesh> entry : meshes.entrySet())
        {
            sb.append(entry.getKey()).append(": mesh yes, ").append(source).append(", ")
                .append(entry.getValue().triangles).append(" triangles\n");
        }
        for (final String name : missing)
        {
            sb.append(name).append(": mesh NO, ").append(source).append('\n');
        }

        sb.append("\nOVERLAPPING PAIRS\n");
        final List<ContactTracker.Overlap> overlaps = contactDetector.overlaps();
        if (overlaps.isEmpty())
        {
            sb.append("none\n");
        }
        for (final ContactTracker.Overlap o : overlaps)
        {
            sb.append(o.a).append(" ~ ").append(o.b).append('\n')
                .append("  touching triangles: ").append(o.triangles).append('\n')
                .append("  contact: ").append(yesNo(o.triangles > 0)).append('\n');
        }

        sb.append("\nRECENT EVENTS (newest first)\n");
        if (debugEvents.isEmpty())
        {
            sb.append("none\n");
        }
        for (final String line : debugEvents)
        {
            sb.append(line).append('\n');
        }

        sb.append("\nPERFORMANCE (10 s average)\n")
            .append("bare body: ").append(String.format("%.3f", bareBody.msPerFrame())).append(" ms/frame\n")
            .append("mesh: ").append(String.format("%.3f", contactDetector.meshMsPerFrame())).append(" ms/frame\n");
        return sb.toString();
    }

    private static String yesNo(final boolean value)
    {
        return value ? "yes" : "no";
    }

    @Subscribe
    public void onGameTick(final GameTick event)
    {
        inPoh = pohDetector.inPoh(client);
        refreshIdentity();
        checkInterceptions();
    }

    /** Client thread: snapshots RSN/install id/world for {@link GameClient}; reallocates only on change. */
    private void refreshIdentity()
    {
        final Player local = client.getLocalPlayer();
        final String name = local == null ? null : local.getName();
        if (client.getGameState() != GameState.LOGGED_IN || name == null)
        {
            identity = null;
            return;
        }
        final String rsn = Text.sanitize(name);
        final String installId = currentInstallId();
        if (installId == null)
        {
            identity = null;
            return;
        }
        final int world = client.getWorld();
        final GameClient.Identity current = identity;
        if (current == null || !current.rsn.equals(rsn) || !current.installId.equals(installId)
            || current.world != world)
        {
            identity = new GameClient.Identity(rsn, installId, world);
        }
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

    /**
     * Once per game tick: a player who starts holding a handegg right after a thrown one stopped
     * being drawn, while in contact with someone, intercepted it. Local display only for now.
     */
    private void checkInterceptions()
    {
        final boolean debug = config.debugLogging();
        final int tick = client.getTickCount();
        final boolean watching = config.enableReporting() && config.reportContacts()
            && client.getGameState() == GameState.LOGGED_IN && inPoh;
        if (debug)
        {
            final String gate = "reporting=" + config.enableReporting() + " contacts=" + config.reportContacts()
                + " inPoh=" + inPoh + " detectInterceptions=" + config.detectInterceptions();
            if (!gate.equals(lastGateLog))
            {
                log.info("[RFL debug] tick {} gate: {}", tick, gate);
                lastGateLog = gate;
            }
        }
        if (debug && watching)
        {
            // Every pair whose mesh bounds overlap, every tick, with its touching triangle count.
            for (final ContactTracker.Overlap o : contactDetector.overlaps())
            {
                log.info("[RFL debug] tick {} touching {}~{}={}", tick, o.a, o.b, o.triangles);
            }
        }
        if (!watching || !config.detectInterceptions())
        {
            interceptionDetector.reset();
            return;
        }

        boolean ballInFlight = false;
        final List<String> projectiles = new ArrayList<>();
        for (Projectile projectile : client.getProjectiles())
        {
            projectiles.add(projectile.getId() + "(" + projectile.getRemainingCycles() + ")");
            if (InterceptionDetector.HANDEGG_PROJECTILES.contains(projectile.getId()))
            {
                ballInFlight = true;
            }
        }

        final Map<String, Integer> weapons = contactDetector.weapons(client);
        final Map<String, List<String>> contacts = contactDetector.collidingNow();
        if (debug)
        {
            logDebugTick(tick, projectiles, ballInFlight, weapons, contacts);
        }

        final List<InterceptionDetector.Interception> found = interceptionDetector.onTick(
            tick, ballInFlight, InterceptionDetector.holders(weapons), contacts);
        if (interceptionDetector.lastCheck() != null)
        {
            recordDebug(interceptionDetector.lastCheck());
            if (debug)
            {
                log.info("[RFL debug] tick {} {}", tick, interceptionDetector.lastCheck());
            }
        }
        if (debug && !contactDetector.missingMeshes().isEmpty())
        {
            log.info("[RFL debug] tick {} no mesh built for {}", tick, contactDetector.missingMeshes());
        }
        if (debug && !found.isEmpty())
        {
            for (final InterceptionDetector.Interception i : found)
            {
                log.info("[RFL debug] tick {} INTERCEPTION receiver={} contacts={}", tick, i.receiver, i.contacts);
            }
        }

        for (final InterceptionDetector.Interception interception : found)
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
    }

    @Subscribe
    public void onClientTick(final ClientTick event)
    {
        refreshDebugPanel();
        final boolean reporting = config.enableReporting();
        final boolean watching = reporting && config.reportContacts()
            && client.getGameState() == GameState.LOGGED_IN && inPoh;

        if (watching)
        {
            for (final RflEvent contactEvent : contactDetector.onFrame(client))
            {
                recordDebugEvent(contactEvent);
                eventQueue.add(contactEvent);
            }
            final List<String> missing = contactDetector.missingMeshes();
            if (!missing.equals(lastMissing))
            {
                if (!missing.isEmpty())
                {
                    recordDebug("no mesh built for " + missing);
                }
                lastMissing = missing;
            }
            return;
        }

        // Not watching this tick (reporting or contacts off, not logged in, or outside the POH): never
        // leave the tracker holding pairs across a period we weren't watching. Close them with
        // a real contact_end when reporting is still on to queue, otherwise there's nothing to
        // send so just clear.
        closeOrResetTracking(reporting);
    }

    @Subscribe
    public void onGameStateChanged(final GameStateChanged event)
    {
        final GameState state = event.getGameState();
        if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
        {
            identity = null;
            closeOrResetTracking(config.enableReporting());
        }
    }

    /** Logs projectiles while any are drawn, weapon-slot changes, and contact changes. */
    private void logDebugTick(int tick, List<String> projectiles, boolean ballInFlight,
        Map<String, Integer> weapons, Map<String, List<String>> contacts)
    {
        final String projectileLog = projectiles.toString();
        if (!projectiles.isEmpty() || !projectileLog.equals(lastProjectileLog))
        {
            log.info("[RFL debug] tick {} projectiles={} handeggInFlight={}", tick, projectileLog, ballInFlight);
            lastProjectileLog = projectileLog;
        }
        for (final Map.Entry<String, Integer> entry : weapons.entrySet())
        {
            final Integer before = lastWeapons.get(entry.getKey());
            if (!entry.getValue().equals(before))
            {
                log.info("[RFL debug] tick {} weapon {}: {} -> {}{}", tick, entry.getKey(), before, entry.getValue(),
                    InterceptionDetector.HANDEGG_ITEMS.contains(entry.getValue()) ? " (handegg)" : "");
            }
        }
        lastWeapons = new HashMap<>(weapons);
        final String contactLog = contacts.toString();
        if (!contactLog.equals(lastContactLog))
        {
            log.info("[RFL debug] tick {} contacts={}", tick, contactLog);
            lastContactLog = contactLog;
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
                recordDebugEvent(endEvent);
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
        if (!config.enableReporting() || !config.reportPlugins())
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
     * Sends one report batch every 10 s while logged in and reporting is enabled — the
     * heartbeat that also carries whatever contact/toggle events queued up since the last send.
     * Runs off the client thread (spec §3). The report is built and the queue drained inside
     * {@link ClientThread#invoke} to read client state safely and keep events and state
     * consistent, but the actual send — including {@link ReportSender#send}'s JSON encoding of
     * the body — is handed off to the injected {@link OkHttpClient}'s own dispatcher executor
     * (already there for the network call itself) so no CPU work runs on the client thread
     * either.
     */
    @Schedule(period = 10, unit = ChronoUnit.SECONDS)
    public void sendReport()
    {
        if (!config.enableReporting())
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
                gameSession.gameId(),
                // Nearby names only leave the client inside a POH (what the Hub description promises).
                inPoh ? contactDetector.seen(client) : Collections.emptyList(),
                snapshotter.snapshot(),
                drained,
                new RflReport.Features(config.reportPlugins(), config.reportContacts(), config.reportNearby()));

            httpClient.dispatcher().executorService().execute(() -> reportSender.send(report, drained));
        });
    }
}
