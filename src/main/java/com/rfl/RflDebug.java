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
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;

/**
 * Everything behind "Debug logging": the RFL panel's Debug tab text (detection gate, players in
 * view, overlapping pairs, recent detection events, mesh timing) and the debug log lines (each
 * logged only when it changes, so the log stays readable). Display and logging only. The panel
 * itself lives in {@link RflPanelController}.
 *
 * <p>Threads: client thread only.
 */
@Slf4j
@Singleton
final class RflDebug
{
    private static final int MAX_EVENTS = 30;

    private final Client client;
    private final RflConfig config;
    private final ContactDetector contactDetector;
    private final BareBody bareBody;
    private final CollisionLog collisionLog;

    private final Deque<String> events = new ArrayDeque<>();
    private List<String> lastMissing = Collections.emptyList();
    private String lastGateLog = "";
    private String lastProjectileLog = "";
    private Map<String, Integer> lastWeapons = Collections.emptyMap();
    private String lastContactLog = "";

    @Inject
    RflDebug(Client client, RflConfig config, ContactDetector contactDetector, BareBody bareBody,
        CollisionLog collisionLog)
    {
        this.client = client;
        this.config = config;
        this.contactDetector = contactDetector;
        this.bareBody = bareBody;
        this.collisionLog = collisionLog;
    }

    /** Adds a line to the Debug tab's recent events (newest first). */
    void record(String line)
    {
        if (!config.showPanel() || !config.debugLogging())
        {
            return;
        }
        events.addFirst("tick " + client.getTickCount() + " " + line);
        while (events.size() > MAX_EVENTS)
        {
            events.removeLast();
        }
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

    /** Whether collisions are saved, and the pairs in contact right now. */
    private void appendCollisions(StringBuilder sb)
    {
        sb.append("\nCOLLISIONS\n");
        sb.append("saving: ").append(config.saveCollisions() ? "on, " + collisionLog.dir() : "off").append('\n');
        List<String> open = contactDetector.inProgress();
        sb.append("in progress: ").append(open.isEmpty() ? "none" : String.join(", ", open)).append('\n');
    }

    /** Client thread: the Debug tab's text. */
    String text(boolean inPoh)
    {
        String source = config.hitboxSource() == RflConfig.HitboxSource.BARE_BODY ? "bare" : "equipped";
        StringBuilder sb = new StringBuilder();
        sb.append("GATE\n")
            .append("logged in: ").append(yesNo(client.getGameState() == GameState.LOGGED_IN)).append('\n')
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

        sb.append("\nOVERLAPPING PAIRS (handegg involved)\n");
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

        appendCollisions(sb);

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
            .append("mesh: ").append(String.format("%.3f", contactDetector.meshMsPerFrame())).append(" ms/frame\n")
            .append("mesh worst frame (last 10 s): ").append(String.format("%.3f", contactDetector.meshWorstMs()))
            .append(" ms\n");
        return sb.toString();
    }

    private static String yesNo(boolean value)
    {
        return value ? "yes" : "no";
    }

    /** Logs the detection gate (the settings and state that decide whether detection runs) when it changes. */
    void logGate(int tick, boolean inPoh)
    {
        String gate = "contacts=" + config.reportContacts()
            + " inPoh=" + inPoh + " detectIncompletes=" + config.detectIncompletes();
        if (!gate.equals(lastGateLog))
        {
            log.info("[RFL debug] tick {} gate: {}", tick, gate);
            lastGateLog = gate;
        }
    }

    /** Logs every checked pair (handegg involved) whose mesh bounds overlap, with its touching triangle count. */
    void logOverlaps(int tick)
    {
        for (ContactTracker.Overlap o : contactDetector.overlaps())
        {
            log.info("[RFL debug] tick {} touching {}~{}={}", tick, o.a, o.b, o.triangles);
        }
    }

    /** Logs projectiles while any are drawn, weapon-slot changes, and contact changes. */
    void logIncompleteInputs(int tick, List<String> projectiles, boolean ballInFlight,
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
                    IncompleteDetector.HANDEGG_ITEMS.contains(entry.getValue()) ? " (handegg)" : "");
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
