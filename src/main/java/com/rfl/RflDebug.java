package com.rfl;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

/**
 * Everything behind "Show debug panel" and "Debug logging": the RFL Debug sidebar panel's
 * lifecycle and text, its recent-events list, and the debug log lines (each logged only when it
 * changes, so the log stays readable). Display and logging only; nothing here is reported.
 *
 * <p>Threads: the panel and its button are touched on the Swing EDT only ({@link #syncPanel},
 * {@link #removePanel}); every other method runs on the client thread.
 */
@Slf4j
@Singleton
final class RflDebug
{
    private static final int MAX_EVENTS = 30;
    private static final long REFRESH_MS = 600;

    private final Client client;
    private final RflConfig config;
    private final ClientToolbar clientToolbar;
    private final ContactDetector contactDetector;
    private final BareBody bareBody;
    private final ObserverLog observerLog;

    // EDT only.
    private DebugPanel panel;
    private NavigationButton button;

    // Client thread only.
    /** Saved observer collisions shown in the panel. */
    private static final int OBSERVER_ROWS = 10;
    private final Deque<String> events = new ArrayDeque<>();
    private long refreshAt;
    private List<String> lastMissing = Collections.emptyList();
    private String lastGateLog = "";
    private String lastProjectileLog = "";
    private Map<String, Integer> lastWeapons = Collections.emptyMap();
    private String lastContactLog = "";

    @Inject
    RflDebug(Client client, RflConfig config, ClientToolbar clientToolbar, ContactDetector contactDetector,
        BareBody bareBody, ObserverLog observerLog)
    {
        this.observerLog = observerLog;
        this.client = client;
        this.config = config;
        this.clientToolbar = clientToolbar;
        this.contactDetector = contactDetector;
        this.bareBody = bareBody;
    }

    /** EDT: adds or removes the debug panel to match the setting. */
    void syncPanel()
    {
        if (!config.showDebugPanel())
        {
            removePanel();
            return;
        }
        if (button != null)
        {
            return;
        }
        panel = new DebugPanel();
        button = NavigationButton.builder()
            .tooltip("RFL Debug")
            .icon(DebugPanel.icon())
            .priority(10)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(button);
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

    /** Adds a line to the panel's recent events (newest first). */
    void record(String line)
    {
        if (!config.showDebugPanel())
        {
            return;
        }
        events.addFirst("tick " + client.getTickCount() + " " + line);
        while (events.size() > MAX_EVENTS)
        {
            events.removeLast();
        }
    }

    void recordEvent(RflEvent event)
    {
        record(event.type + (event.contactId == null ? "" : " #" + event.contactId) + " at " + event.x + ","
            + event.y + "," + event.plane + (event.depth == null ? "" : " depth " + event.depth)
            + (event.ball == null ? "" : " ball " + event.ball));
    }

    /** Records the players without a mesh whenever that list changes. */
    void recordMissing(List<String> missing)
    {
        if (missing.equals(lastMissing))
        {
            return;
        }
        if (!missing.isEmpty())
        {
            record("no mesh built for " + missing);
        }
        lastMissing = missing;
    }

    /** About every 600 ms: snapshots detection state as text and hands it to the EDT. */
    void refresh(boolean inPoh)
    {
        long now = System.currentTimeMillis();
        if (!config.showDebugPanel() || now - refreshAt < REFRESH_MS)
        {
            return;
        }
        refreshAt = now;
        String text = text(inPoh);
        SwingUtilities.invokeLater(() ->
        {
            if (panel != null)
            {
                panel.show(text);
            }
        });
    }

    /** Observer mode: collisions in progress, then the latest saved ones (local only, never sent). */
    private void appendObserver(StringBuilder sb)
    {
        sb.append("\nOBSERVER (saved locally, not sent)\n");
        List<String> open = contactDetector.observing();
        sb.append("in progress: ").append(open.isEmpty() ? "none" : String.join(", ", open)).append('\n');
        List<ObservedCollision> recent = observerLog.recent();
        if (recent.isEmpty())
        {
            sb.append("no saved collisions yet\n");
        }
        for (int i = 0; i < Math.min(OBSERVER_ROWS, recent.size()); i++)
        {
            sb.append(ObserverLog.row(recent.get(i))).append('\n');
        }
    }

    private String text(boolean inPoh)
    {
        String source = config.hitboxSource() == RflConfig.HitboxSource.BARE_BODY ? "bare" : "equipped";
        StringBuilder sb = new StringBuilder();
        sb.append("GATE\n")
            .append("logged in: ").append(yesNo(client.getGameState() == GameState.LOGGED_IN)).append('\n')
            .append("reporting: ").append(yesNo(config.enableReporting())).append('\n')
            .append("observer: ").append(yesNo(config.observerMode())).append('\n')
            .append("in POH: ").append(yesNo(inPoh)).append('\n')
            .append("detect contacts: ").append(yesNo(config.reportContacts())).append('\n')
            .append("hitbox source: ").append(source).append("\n\n");

        sb.append("PLAYERS IN VIEW\n");
        Map<String, PosedMesh> meshes = new TreeMap<>(contactDetector.meshes());
        List<String> missing = contactDetector.missingMeshes();
        if (meshes.isEmpty() && missing.isEmpty())
        {
            sb.append("none (or detection not running)\n");
        }
        for (Map.Entry<String, PosedMesh> entry : meshes.entrySet())
        {
            sb.append(entry.getKey()).append(": mesh yes, ").append(source).append(", ")
                .append(entry.getValue().triangles).append(" triangles\n");
        }
        for (String name : missing)
        {
            sb.append(name).append(": mesh NO, ").append(source).append('\n');
        }

        sb.append("\nOVERLAPPING PAIRS\n");
        List<ContactTracker.Overlap> overlaps = contactDetector.overlaps();
        if (overlaps.isEmpty())
        {
            sb.append("none\n");
        }
        for (ContactTracker.Overlap o : overlaps)
        {
            sb.append(o.a).append(" ~ ").append(o.b).append('\n')
                .append("  touching triangles: ").append(o.triangles).append('\n')
                .append("  contact: ").append(yesNo(o.triangles > 0)).append('\n');
        }

        if (config.observerMode())
        {
            appendObserver(sb);
        }

        sb.append("\nRECENT EVENTS (newest first)\n");
        if (events.isEmpty())
        {
            sb.append("none\n");
        }
        for (String line : events)
        {
            sb.append(line).append('\n');
        }

        sb.append("\nPERFORMANCE (10 s average)\n")
            .append("bare body: ").append(String.format("%.3f", bareBody.msPerFrame())).append(" ms/frame\n")
            .append("mesh: ").append(String.format("%.3f", contactDetector.meshMsPerFrame())).append(" ms/frame\n");
        return sb.toString();
    }

    private static String yesNo(boolean value)
    {
        return value ? "yes" : "no";
    }

    /** Logs the detection gate (the settings and state that decide whether detection runs) when it changes. */
    void logGate(int tick, boolean inPoh)
    {
        String gate = "reporting=" + config.enableReporting() + " observer=" + config.observerMode()
            + " contacts=" + config.reportContacts()
            + " inPoh=" + inPoh + " detectInterceptions=" + config.detectInterceptions();
        if (!gate.equals(lastGateLog))
        {
            log.info("[RFL debug] tick {} gate: {}", tick, gate);
            lastGateLog = gate;
        }
    }

    /** Logs every pair whose mesh bounds overlap, with its touching triangle count. */
    void logOverlaps(int tick)
    {
        for (ContactTracker.Overlap o : contactDetector.overlaps())
        {
            log.info("[RFL debug] tick {} touching {}~{}={}", tick, o.a, o.b, o.triangles);
        }
    }

    /** Logs projectiles while any are drawn, weapon-slot changes, and contact changes. */
    void logInterceptionInputs(int tick, List<String> projectiles, boolean ballInFlight,
        Map<String, Integer> weapons, Map<String, List<String>> contacts)
    {
        String projectileLog = projectiles.toString();
        if (!projectiles.isEmpty() || !projectileLog.equals(lastProjectileLog))
        {
            log.info("[RFL debug] tick {} projectiles={} handeggInFlight={}", tick, projectileLog, ballInFlight);
            lastProjectileLog = projectileLog;
        }
        for (Map.Entry<String, Integer> entry : weapons.entrySet())
        {
            Integer before = lastWeapons.get(entry.getKey());
            if (!entry.getValue().equals(before))
            {
                log.info("[RFL debug] tick {} weapon {}: {} -> {}{}", tick, entry.getKey(), before, entry.getValue(),
                    InterceptionDetector.HANDEGG_ITEMS.contains(entry.getValue()) ? " (handegg)" : "");
            }
        }
        lastWeapons = new HashMap<>(weapons);
        String contactLog = contacts.toString();
        if (!contactLog.equals(lastContactLog))
        {
            log.info("[RFL debug] tick {} contacts={}", tick, contactLog);
            lastContactLog = contactLog;
        }
    }
}
