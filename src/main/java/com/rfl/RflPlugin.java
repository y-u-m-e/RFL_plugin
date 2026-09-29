package com.rfl;

import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;

import com.google.inject.Provides;

import okhttp3.OkHttpClient;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
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
import net.runelite.client.util.Text;

/**
 * RFL match audit plugin. Reports enabled plugins, RSN, world, nearby players and POH contact
 * events to the audit gateway every 10 s while logged in and reporting is enabled (spec §3).
 */
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
    }

    @Override
    protected void shutDown()
    {
        contactDetector.reset();
    }

    @Subscribe
    public void onGameTick(final GameTick event)
    {
        inPoh = pohDetector.inPoh(client);
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
