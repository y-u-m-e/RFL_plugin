package com.rfl;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;

import okhttp3.OkHttpClient;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
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
    private ContactOverlapOverlay contactOverlapOverlay;

    @Inject
    private HitboxOverlay hitboxOverlay;

    @Inject
    private ContactHighlights contactHighlights;

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
        if (config.installId() == null || config.installId().isEmpty())
        {
            config.installId(UUID.randomUUID().toString());
        }
        overlayManager.add(contactHighlightOverlay);
        overlayManager.add(contactOverlapOverlay);
        overlayManager.add(hitboxOverlay);
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(contactHighlightOverlay);
        overlayManager.remove(contactOverlapOverlay);
        overlayManager.remove(hitboxOverlay);
        interceptionDetector.reset();
        contactDetector.reset();
    }

    @Subscribe
    public void onGameTick(final GameTick event)
    {
        inPoh = pohDetector.inPoh(client);
        checkInterceptions();
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
            // Every overlapping pair's deepest part pair, every tick: what START_DEPTH is measured from.
            for (final ContactTracker.Overlap o : contactDetector.overlaps())
            {
                log.info("[RFL debug] tick {} depth {}.{}~{}.{}={} ({})", tick, o.a, o.partA, o.b, o.partB, o.depth,
                    o.contact ? "contact" : "graze");
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
        final Map<String, List<String>> contacts = contactDetector.contactsByPlayer();
        if (debug)
        {
            logDebugTick(tick, projectiles, ballInFlight, weapons, contacts);
        }

        final List<InterceptionDetector.Interception> found = interceptionDetector.onTick(
            tick, ballInFlight, ContactDetector.handeggHolders(weapons), contacts);
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
            final Body body = contactDetector.bodies().get(interception.receiver);
            if (config.highlightInterceptions() && body != null)
            {
                contactHighlights.addInterception(body.centreX, body.centreY, System.currentTimeMillis());
            }
        }
    }

    @Subscribe
    public void onClientTick(final ClientTick event)
    {
        final boolean reporting = config.enableReporting();
        final boolean watching = reporting && config.reportContacts()
            && client.getGameState() == GameState.LOGGED_IN && inPoh;

        if (watching)
        {
            for (final RflEvent contactEvent : contactDetector.onFrame(client))
            {
                eventQueue.add(contactEvent);
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

    private void closeOrResetTracking(final boolean queueEnds)
    {
        if (queueEnds)
        {
            for (final RflEvent endEvent : contactDetector.endAll(client))
            {
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

            final List<RflEvent> drained = eventQueue.drain(EventQueue.MAX_BATCH);
            final RflReport report = new RflReport(
                rsn,
                config.installId(),
                client.getWorld(),
                System.currentTimeMillis(),
                inPoh,
                config.matchCode(),
                config.team(),
                // Nearby names only leave the client inside a POH (what the Hub description promises).
                inPoh ? contactDetector.seen(client) : Collections.emptyList(),
                snapshotter.snapshot(),
                drained,
                new RflReport.Features(config.reportPlugins(), config.reportContacts(), config.reportNearby()));

            httpClient.dispatcher().executorService().execute(() -> reportSender.send(report, drained));
        });
    }
}
