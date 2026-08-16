package com.playercollision;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;

/**
 * Handles RFL plugin-list exporting, ref checks, and tamper-evident local match audit logging.
 */
@PluginDescriptor(
    name = "RFL Plugin Ref Check",
    description = "Exports plugin lists, checks blacklist matches, and records tamper-evident local match logs",
    tags = {"rfl", "plugin", "ref", "blacklist", "audit"}
)
public class PlayerCollisionPlugin extends Plugin
{
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final BufferedImage PANEL_ICON = createPanelIcon();
    private static final String CONFIG_GROUP = "playercollision";
    private static final String HEADER_PREFIX = "RFL Plugin List Export";
    private static final String REF_MENU_OPTION = "RFL Ref Target";
    private static final int MAX_STORED_PLAYER_RESULTS = 40;
    private static final String BLACKLIST_FILE_NAME = "blacklist.txt";

    @Inject
    private Client client;

    @Inject
    private PlayerCollisionConfig config;

    @Inject
    private PluginManager pluginManager;

    @Inject
    private MenuManager menuManager;

    @Inject
    private Notifier notifier;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private PlayerCollisionPanel panel;

    private NavigationButton navigationButton;
    private String lastSummary = "No checks run yet.";
    private String lastDetails = "Use \"Copy My Plugin List\" to export your active plugins.\n"
        + "Use \"Check Clipboard List\" to analyze a pasted player list.";
    private final Map<String, PlayerCheckRecord> playerChecksByName = new LinkedHashMap<>();
    private String selectedTargetPlayer = "";
    private boolean playerMenuRegistered;

    private boolean sessionActive;
    private String sessionMatchId = "";
    private String sessionNonce = "";
    private String sessionPreviousHash = "GENESIS";
    private int sessionRecordIndex;
    private int ticksSinceSnapshot;
    private Path activeSessionLogPath;
    private Path latestSessionLogPath;
    private String sessionStatus = "Session idle.";
    private boolean sessionBlacklistDetected;
    private boolean blacklistActiveLastTick;

    private final Set<String> cachedBlacklistEntries = new LinkedHashSet<>();
    private long cachedBlacklistLastModifiedMillis = -1L;

    /**
     * Provides the plugin configuration through RuneLite's config manager.
     *
     * @param configManager central RuneLite config manager
     * @return plugin config instance
     */
    @Provides
    PlayerCollisionConfig provideConfig(final ConfigManager configManager)
    {
        return configManager.getConfig(PlayerCollisionConfig.class);
    }

    /**
     * Initializes side-panel UI and optional ref tools when the plugin starts.
     */
    @Override
    protected void startUp()
    {
        panel.setPlugin(this);
        navigationButton = NavigationButton.builder()
            .tooltip("RFL Plugin Ref Check")
            .priority(7)
            .icon(PANEL_ICON)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navigationButton);

        syncPlayerMenuOption();
        ensureBlacklistFileExists();
        refreshPanel();
        if (config.notifyLocalBlacklistHit())
        {
            runLocalBlacklistCheck();
        }
    }

    /**
     * Cleans up menu hooks and UI references when the plugin stops.
     */
    @Override
    protected void shutDown()
    {
        removePlayerMenuOptionIfRegistered();
        stopMatchSessionInternal(true);

        if (navigationButton != null)
        {
            clientToolbar.removeNavigation(navigationButton);
            navigationButton = null;
        }
    }

    /**
     * Applies dynamic config changes for blacklist behavior and menu toggles.
     *
     * @param event config changed event
     */
    @Subscribe
    public void onConfigChanged(final ConfigChanged event)
    {
        if (!CONFIG_GROUP.equals(event.getGroup()))
        {
            return;
        }

        if ("notifyLocalBlacklistHit".equals(event.getKey()))
        {
            if (config.notifyLocalBlacklistHit())
            {
                runLocalBlacklistCheck();
            }
        }

        if ("enablePlayerMenuOption".equals(event.getKey()))
        {
            syncPlayerMenuOption();
        }
    }

    /**
     * Runs periodic snapshots while match session monitoring is active.
     *
     * @param event game tick event
     */
    @Subscribe
    public void onGameTick(final GameTick event)
    {
        if (!sessionActive || !config.enableMatchSessionMonitor())
        {
            return;
        }

        final List<PluginEntry> entries = collectEnabledPluginEntries();
        final AnalysisResult analysis = analyzePluginEntries(entries, "Session snapshot");

        final boolean hasBlacklistHits = !analysis.blacklistedPlugins.isEmpty();
        if (hasBlacklistHits)
        {
            sessionBlacklistDetected = true;
        }

        if (hasBlacklistHits && !blacklistActiveLastTick)
        {
            writeSessionRecord("BLACKLIST_HIT", "detected-during-match", entries, analysis);
            notifier.notify("RFL alert: blacklisted plugin detected during active match session.");
        }

        blacklistActiveLastTick = hasBlacklistHits;

        ticksSinceSnapshot++;
        final int snapshotEveryTicks = Math.max(1, Math.round(config.snapshotIntervalSeconds() / 0.6f));
        if (ticksSinceSnapshot < snapshotEveryTicks)
        {
            return;
        }

        ticksSinceSnapshot = 0;
        writeSnapshotRecord("SNAPSHOT", entries, analysis);
    }

    /**
     * Handles custom player right-click action selection when enabled for referee workflows.
     *
     * @param event menu option click event
     */
    @Subscribe
    public void onMenuOptionClicked(final MenuOptionClicked event)
    {
        if (!REF_MENU_OPTION.equals(event.getMenuOption()))
        {
            return;
        }

        selectedTargetPlayer = cleanPlayerName(event.getMenuTarget());
        if (selectedTargetPlayer.isEmpty())
        {
            selectedTargetPlayer = "Unknown";
        }

        lastSummary = "Selected target player: " + selectedTargetPlayer;
        lastDetails = composeDetails(
            "Paste " + selectedTargetPlayer + "'s exported plugin list into clipboard, then click "
                + "\"Check Clipboard List\"."
        );
        refreshPanel();
        notifier.notify("RFL target selected: " + selectedTargetPlayer);
    }

    /**
     * Starts a new local tamper-evident match session log.
     *
     * @param requestedMatchId match identifier entered by the user
     */
    public void startMatchSession(final String requestedMatchId)
    {
        if (!config.enableMatchSessionMonitor())
        {
            setOutput(
                "Session monitor disabled in config.",
                "Enable \"Match Session Monitor\" in plugin settings, then start again."
            );
            return;
        }

        if (sessionActive)
        {
            setOutput("Session already active.", "Current session: " + sessionMatchId + "\n" + sessionStatus);
            return;
        }

        final String sanitizedMatchId = sanitizeMatchId(requestedMatchId);
        final LocalDateTime now = LocalDateTime.now();
        final String fileStem = now.format(FILE_TIME_FORMATTER) + "-" + sanitizedMatchId;
        final Path logDirectory = getSessionLogDirectory();
        final Path logPath = logDirectory.resolve(fileStem + ".rfl-audit.log");

        try
        {
            Files.createDirectories(logDirectory);
            final List<String> headerLines = new ArrayList<>();
            headerLines.add("# RFL Local Tamper-Evident Match Log");
            headerLines.add("# matchId=" + sanitizedMatchId);
            headerLines.add("# startedAt=" + TIME_FORMATTER.format(now));
            headerLines.add("# format=v1");
            Files.write(logPath, headerLines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        catch (IOException ex)
        {
            setOutput("Failed to start match session.", "Could not create log file:\n" + ex.getMessage());
            return;
        }

        sessionActive = true;
        sessionMatchId = sanitizedMatchId;
        sessionNonce = UUID.randomUUID().toString();
        sessionPreviousHash = "GENESIS";
        sessionRecordIndex = 0;
        ticksSinceSnapshot = 0;
        activeSessionLogPath = logPath;
        latestSessionLogPath = logPath;
        sessionBlacklistDetected = false;
        blacklistActiveLastTick = false;
        sessionStatus = "Session active: " + sessionMatchId + " (" + activeSessionLogPath + ")";

        final List<PluginEntry> initialEntries = collectEnabledPluginEntries();
        final AnalysisResult initialAnalysis = analyzePluginEntries(initialEntries, "Session snapshot");
        writeSessionRecord("START", "nonce=" + sessionNonce, initialEntries, initialAnalysis);
        writeSnapshotRecord("SNAPSHOT", initialEntries, initialAnalysis);

        setOutput("Started match session: " + sessionMatchId, "Log file:\n" + activeSessionLogPath);
        notifier.notify("RFL session started: " + sessionMatchId);
    }

    /**
     * Stops the currently running local match session and writes a final record.
     */
    public void stopMatchSession()
    {
        if (!sessionActive)
        {
            setOutput("No active session.", "Start a match session before stopping.");
            return;
        }

        stopMatchSessionInternal(false);
        notifier.notify("RFL session stopped: " + sessionMatchId);
    }

    /**
     * Verifies the latest local session log hash chain for tamper evidence.
     */
    public void verifyLatestSessionLog()
    {
        if (latestSessionLogPath == null || !Files.exists(latestSessionLogPath))
        {
            setOutput("No session log available.", "Run and stop at least one session before verification.");
            return;
        }

        try
        {
            final List<String> lines = Files.readAllLines(latestSessionLogPath, StandardCharsets.UTF_8);
            String expectedPrevious = "GENESIS";
            int records = 0;
            int blacklistHits = 0;
            int unofficialHits = 0;

            for (String line : lines)
            {
                final String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#"))
                {
                    continue;
                }

                final String[] parts = trimmed.split("\\|", -1);
                if (parts.length != 12)
                {
                    setOutput("Verification failed.", "Malformed log line:\n" + trimmed);
                    return;
                }

                final String canonical = String.join(
                    "|",
                    parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], parts[6], parts[7], parts[8], parts[9], parts[10]
                );
                final String computedHash = sha256Hex(canonical);
                if (!computedHash.equals(parts[11]))
                {
                    setOutput("Verification failed.", "Record hash mismatch at index " + parts[0] + ".");
                    return;
                }

                if (!expectedPrevious.equals(parts[10]))
                {
                    setOutput("Verification failed.", "Chain break at index " + parts[0] + ".");
                    return;
                }

                expectedPrevious = parts[11];
                records++;
                blacklistHits += parseIntSafe(parts[5]);
                unofficialHits += parseIntSafe(parts[6]);
            }

            sessionStatus = "Latest log verified: " + latestSessionLogPath.getFileName();
            setOutput(
                "Verification passed.",
                "Records: " + records + "\n"
                    + "Total blacklist hits: " + blacklistHits + "\n"
                    + "Total unofficial hits: " + unofficialHits + "\n"
                    + "File: " + latestSessionLogPath
            );
        }
        catch (IOException ex)
        {
            setOutput("Verification failed.", "Unable to read log file:\n" + ex.getMessage());
        }
    }

    /**
     * Copies enabled plugin list to clipboard in a format suitable for manual referee checks.
     */
    public void copyMyPluginListToClipboard()
    {
        final List<PluginEntry> enabledEntries = collectEnabledPluginEntries();
        final String exportText = buildExportText(enabledEntries);

        copyToClipboard(exportText);
        setOutput("Copied " + enabledEntries.size() + " enabled plugins to clipboard.", exportText);
        notifier.notify("RFL plugin list copied to clipboard.");
    }

    /**
     * Reads clipboard text and checks it against blacklist and unofficial flags.
     */
    public void checkClipboardList()
    {
        final String clipboardText = readClipboardText();
        if (clipboardText == null || clipboardText.trim().isEmpty())
        {
            setOutput("Clipboard is empty.", "Copy or paste a player's plugin list, then run this check again.");
            return;
        }

        final AnalysisResult analysis = analyzePluginListText(clipboardText);
        final String headerPlayerName = extractHeaderPlayerName(clipboardText);
        final String resolvedPlayerName = resolveCheckedPlayerName(headerPlayerName);
        final String mismatchNote = buildPlayerMismatchNote(resolvedPlayerName, headerPlayerName);

        storePlayerResult(resolvedPlayerName, analysis);
        setOutput("Checked " + resolvedPlayerName + ": " + analysis.summary, analysis.details + mismatchNote);
        if (config.notifyRefOnClipboardAnalysis())
        {
            notifier.notify("Checked " + resolvedPlayerName + ": " + analysis.summary);
        }
    }

    /**
     * Clears panel output and stored player check results.
     */
    public void clearResultText()
    {
        selectedTargetPlayer = "";
        playerChecksByName.clear();
        sessionStatus = sessionActive ? "Session active: " + sessionMatchId : "Session idle.";
        setOutput(
            "No checks run yet.",
            "Use \"Copy My Plugin List\" to export your active plugins.\n"
                + "Use \"Check Clipboard List\" to analyze a pasted player list."
        );
    }

    /**
     * Applies output text and refreshes the panel.
     *
     * @param summary short summary text
     * @param coreDetails primary details text
     */
    private void setOutput(final String summary, final String coreDetails)
    {
        lastSummary = summary;
        lastDetails = composeDetails(coreDetails);
        refreshPanel();
    }

    /**
     * Composes panel detail text with session and stored-player sections.
     *
     * @param coreDetails primary details section
     * @return final panel details text
     */
    private String composeDetails(final String coreDetails)
    {
        return coreDetails
            + "\n\nBlacklist File:\n- " + getBlacklistFilePath()
            + "\n\nSession Status:\n- " + sessionStatus
            + "\n\n" + buildStoredPlayerResultsSection();
    }

    /**
     * Stops the active session and writes a final log record.
     *
     * @param silent true when stopping during plugin shutdown
     */
    private void stopMatchSessionInternal(final boolean silent)
    {
        if (!sessionActive)
        {
            return;
        }

        final List<PluginEntry> finalEntries = collectEnabledPluginEntries();
        final AnalysisResult finalAnalysis = analyzePluginEntries(finalEntries, "Session snapshot");
        writeSessionRecord("END", "session-complete", finalEntries, finalAnalysis);
        final String stoppedMatch = sessionMatchId;
        final Path stoppedPath = activeSessionLogPath;

        sessionActive = false;
        sessionMatchId = "";
        sessionNonce = "";
        sessionPreviousHash = "GENESIS";
        sessionRecordIndex = 0;
        ticksSinceSnapshot = 0;
        activeSessionLogPath = null;
        blacklistActiveLastTick = false;
        final boolean detectedDuringSession = sessionBlacklistDetected;
        sessionBlacklistDetected = false;
        sessionStatus = "Session stopped. Latest log: " + stoppedPath;

        if (!silent)
        {
            final String detectionLine = detectedDuringSession
                ? "\nBlacklisted plugin was detected at least once during this session."
                : "\nNo blacklisted plugin detections were recorded during this session.";
            setOutput("Stopped match session: " + stoppedMatch, "Log file:\n" + stoppedPath + detectionLine);
        }
    }

    /**
     * Writes one timed snapshot record using the current enabled plugin list.
     *
     * @param eventType event type label to store
     */
    private void writeSnapshotRecord(
        final String eventType,
        final List<PluginEntry> entries,
        final AnalysisResult analysis
    )
    {
        writeSessionRecord(eventType, "", entries, analysis);
    }

    /**
     * Writes one hash-chained session record to the active log file.
     *
     * @param eventType event type name
     * @param metadata metadata text stored with the record
     */
    private void writeSessionRecord(
        final String eventType,
        final String metadata,
        final List<PluginEntry> entries,
        final AnalysisResult analysis
    )
    {
        if (!sessionActive || activeSessionLogPath == null)
        {
            return;
        }

        final String pluginHash = hashPluginEntryList(entries);
        final String metadataPayload = metadata == null ? "" : metadata;
        final String blacklistPayload = String.join("\n", analysis.blacklistedPlugins);
        final String unofficialPayload = String.join("\n", analysis.unofficialPlugins);

        final String metadataB64 = base64(metadataPayload);
        final String blacklistB64 = base64(blacklistPayload);
        final String unofficialB64 = base64(unofficialPayload);
        final String timestamp = TIME_FORMATTER.format(LocalDateTime.now());
        final int index = sessionRecordIndex++;

        final String canonical = String.join(
            "|",
            Integer.toString(index),
            eventType,
            timestamp,
            pluginHash,
            Integer.toString(entries.size()),
            Integer.toString(analysis.blacklistedPlugins.size()),
            Integer.toString(analysis.unofficialPlugins.size()),
            metadataB64,
            blacklistB64,
            unofficialB64,
            sessionPreviousHash
        );
        final String recordHash = sha256Hex(canonical);
        final String line = canonical + "|" + recordHash;

        try
        {
            Files.write(
                activeSessionLogPath,
                Collections.singletonList(line),
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE
            );
            sessionPreviousHash = recordHash;
        }
        catch (IOException ex)
        {
            sessionStatus = "Session write failed: " + ex.getMessage();
            setOutput("Session logging error.", "Could not append to session log:\n" + ex.getMessage());
            stopMatchSessionInternal(true);
        }
    }

    /**
     * Performs a local blacklist check against currently enabled plugins.
     */
    private void runLocalBlacklistCheck()
    {
        final List<PluginEntry> enabledEntries = collectEnabledPluginEntries();
        final AnalysisResult analysis = analyzePluginEntries(enabledEntries, "Local enabled plugins");
        setOutput(analysis.summary, analysis.details);

        if (!analysis.blacklistedPlugins.isEmpty())
        {
            notifier.notify("RFL warning: blacklisted plugin(s) detected in your enabled list.");
        }
    }

    /**
     * Collects enabled plugin entries from the current RuneLite instance.
     *
     * @return sorted enabled plugin entries
     */
    private List<PluginEntry> collectEnabledPluginEntries()
    {
        final List<PluginEntry> entries = new ArrayList<>();
        for (Plugin plugin : pluginManager.getPlugins())
        {
            if (!isPluginEnabled(plugin))
            {
                continue;
            }

            final String name = pluginDisplayName(plugin);
            final PluginSource source = pluginSource(plugin);
            entries.add(new PluginEntry(name, source));
        }

        entries.sort(Comparator.comparing((PluginEntry entry) -> entry.name.toLowerCase(Locale.ENGLISH)));
        return entries;
    }

    /**
     * Ensures the optional player menu option state matches current config.
     */
    private void syncPlayerMenuOption()
    {
        if (config.enablePlayerMenuOption() && !playerMenuRegistered)
        {
            menuManager.addPlayerMenuItem(REF_MENU_OPTION);
            playerMenuRegistered = true;
            return;
        }

        if (!config.enablePlayerMenuOption())
        {
            removePlayerMenuOptionIfRegistered();
        }
    }

    /**
     * Removes custom player menu option if currently registered.
     */
    private void removePlayerMenuOptionIfRegistered()
    {
        if (!playerMenuRegistered)
        {
            return;
        }

        menuManager.removePlayerMenuItem(REF_MENU_OPTION);
        playerMenuRegistered = false;
    }

    /**
     * Builds export text for clipboard copy.
     *
     * @param entries plugin entries to export
     * @return formatted export text
     */
    private String buildExportText(final List<PluginEntry> entries)
    {
        final String playerName = client.getLocalPlayer() != null ? safeText(client.getLocalPlayer().getName()) : "Unknown";
        final StringBuilder builder = new StringBuilder();
        builder.append(HEADER_PREFIX).append('\n');
        builder.append("Player: ").append(playerName).append('\n');
        builder.append("Generated: ").append(TIME_FORMATTER.format(LocalDateTime.now())).append('\n');
        builder.append("Count: ").append(entries.size()).append("\n\n");

        for (PluginEntry entry : entries)
        {
            builder.append("- ").append(entry.name);
            if (config.includeSourceInExport())
            {
                builder.append(" [").append(entry.source.name()).append(']');
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    /**
     * Extracts a player name from exported clipboard text header when available.
     *
     * @param pluginListText raw clipboard text
     * @return player name from "Player:" header or empty when unavailable
     */
    private String extractHeaderPlayerName(final String pluginListText)
    {
        final String[] lines = pluginListText.split("\\r?\\n");
        for (String line : lines)
        {
            final String trimmed = line.trim();
            if (!trimmed.toLowerCase(Locale.ENGLISH).startsWith("player:"))
            {
                continue;
            }

            final String rawName = trimmed.substring("player:".length()).trim();
            return cleanPlayerName(rawName);
        }
        return "";
    }

    /**
     * Resolves which player name should be used for clipboard check result storage.
     *
     * @param headerPlayerName player name parsed from clipboard header
     * @return resolved player name
     */
    private String resolveCheckedPlayerName(final String headerPlayerName)
    {
        if (!selectedTargetPlayer.trim().isEmpty())
        {
            return selectedTargetPlayer;
        }
        if (!headerPlayerName.trim().isEmpty())
        {
            return headerPlayerName;
        }
        return "Unknown Player";
    }

    /**
     * Builds a note when selected target differs from clipboard header player.
     *
     * @param resolvedPlayerName player used for storage
     * @param headerPlayerName player parsed from clipboard
     * @return mismatch note text or empty string
     */
    private String buildPlayerMismatchNote(final String resolvedPlayerName, final String headerPlayerName)
    {
        if (selectedTargetPlayer.trim().isEmpty() || headerPlayerName.trim().isEmpty())
        {
            return "";
        }
        if (selectedTargetPlayer.equalsIgnoreCase(headerPlayerName))
        {
            return "";
        }

        return "\n\nNote: selected target \"" + resolvedPlayerName + "\" differs from clipboard header \""
            + headerPlayerName + "\".";
    }

    /**
     * Analyzes clipboard text in export format and checks blacklist and source flags.
     *
     * @param pluginListText plugin list text from clipboard
     * @return analysis result for panel and notifications
     */
    private AnalysisResult analyzePluginListText(final String pluginListText)
    {
        final List<PluginEntry> parsedEntries = new ArrayList<>();
        final String[] lines = pluginListText.split("\\r?\\n");
        for (String line : lines)
        {
            final String trimmed = line.trim();
            if (!trimmed.startsWith("- "))
            {
                continue;
            }

            parsedEntries.add(parsePluginEntry(trimmed.substring(2)));
        }

        if (parsedEntries.isEmpty())
        {
            return new AnalysisResult(
                "No plugin entries found in clipboard text.",
                "Expected exported lines that start with \"- \".",
                Collections.emptyList(),
                Collections.emptyList()
            );
        }

        return analyzePluginEntries(parsedEntries, "Clipboard plugin list");
    }

    /**
     * Stores analysis result under a player entry and maintains recency ordering.
     *
     * @param playerName checked player name
     * @param analysis latest analysis result
     */
    private void storePlayerResult(final String playerName, final AnalysisResult analysis)
    {
        final String normalizedName = cleanPlayerName(playerName);
        final String key = normalizedName.isEmpty() ? "Unknown Player" : normalizedName;
        final PlayerCheckRecord record = new PlayerCheckRecord(
            key,
            LocalDateTime.now().format(TIME_FORMATTER),
            analysis.summary,
            analysis.blacklistedPlugins.size(),
            analysis.unofficialPlugins.size()
        );

        playerChecksByName.remove(key);
        playerChecksByName.put(key, record);

        while (playerChecksByName.size() > MAX_STORED_PLAYER_RESULTS)
        {
            final String oldestKey = playerChecksByName.keySet().iterator().next();
            playerChecksByName.remove(oldestKey);
        }
    }

    /**
     * Builds a section showing recent per-player check results for referee tracking.
     *
     * @return formatted multi-line player result summary section
     */
    private String buildStoredPlayerResultsSection()
    {
        if (playerChecksByName.isEmpty())
        {
            return "Recent Player Results:\n- No player checks stored yet.";
        }

        final List<PlayerCheckRecord> records = new ArrayList<>(playerChecksByName.values());
        Collections.reverse(records);

        final StringBuilder builder = new StringBuilder();
        builder.append("Recent Player Results (newest first):\n");
        for (PlayerCheckRecord record : records)
        {
            builder.append("- ")
                .append(record.playerName)
                .append(" | ")
                .append(record.checkedAt)
                .append(" | blacklist=")
                .append(record.blacklistHits)
                .append(" | unofficial=")
                .append(record.unofficialHits)
                .append(" | ")
                .append(record.summary)
                .append('\n');
        }
        return builder.toString();
    }

    /**
     * Runs blacklist and unofficial checks on parsed plugin entries.
     *
     * @param entries plugin entries to evaluate
     * @param sourceLabel label identifying where data came from
     * @return analysis result object
     */
    private AnalysisResult analyzePluginEntries(final List<PluginEntry> entries, final String sourceLabel)
    {
        final Set<String> blacklist = loadBlacklistEntriesFromFile();
        final List<String> blacklistedPlugins = new ArrayList<>();
        final List<String> unofficialPlugins = new ArrayList<>();

        for (PluginEntry entry : entries)
        {
            final String lowerName = entry.name.toLowerCase(Locale.ENGLISH);
            for (String banned : blacklist)
            {
                if (lowerName.contains(banned))
                {
                    blacklistedPlugins.add(entry.name + " (matched: " + banned + ")");
                    break;
                }
            }

            if (entry.source == PluginSource.UNOFFICIAL)
            {
                unofficialPlugins.add(entry.name);
            }
        }

        final StringBuilder details = new StringBuilder();
        details.append(sourceLabel).append('\n');
        details.append("Entries checked: ").append(entries.size()).append('\n');
        details.append("Blacklist hits: ").append(blacklistedPlugins.size()).append('\n');
        details.append("Unofficial hits: ").append(unofficialPlugins.size()).append('\n');

        if (!blacklistedPlugins.isEmpty())
        {
            details.append("\nBlacklisted plugin hits:\n");
            for (String hit : blacklistedPlugins)
            {
                details.append("- ").append(hit).append('\n');
            }
        }
        else
        {
            details.append("\nNo blacklist hits found.\n");
        }

        if (!unofficialPlugins.isEmpty())
        {
            details.append("\nUnofficial plugins detected:\n");
            for (String unofficial : unofficialPlugins)
            {
                details.append("- ").append(unofficial).append('\n');
            }
        }
        else
        {
            details.append("\nNo unofficial plugins detected in parsed data.\n");
        }

        final String summary;
        if (!blacklistedPlugins.isEmpty())
        {
            summary = "Blacklist alert: " + blacklistedPlugins.size() + " hit(s) found.";
        }
        else if (!unofficialPlugins.isEmpty())
        {
            summary = "No blacklist hits. Unofficial plugins found: " + unofficialPlugins.size() + ".";
        }
        else
        {
            summary = "Check clear: no blacklist or unofficial plugins detected.";
        }

        return new AnalysisResult(summary, details.toString(), blacklistedPlugins, unofficialPlugins);
    }

    /**
     * Parses one exported plugin entry line into a structured object.
     *
     * @param rawEntry exported entry text without the "- " prefix
     * @return parsed plugin entry
     */
    private PluginEntry parsePluginEntry(final String rawEntry)
    {
        final int sourceStart = rawEntry.lastIndexOf(" [");
        final int sourceEnd = rawEntry.endsWith("]") ? rawEntry.length() - 1 : -1;
        if (sourceStart > 0 && sourceEnd > sourceStart)
        {
            final String name = rawEntry.substring(0, sourceStart).trim();
            final String rawSource = rawEntry.substring(sourceStart + 2, sourceEnd).trim();
            return new PluginEntry(name, PluginSource.fromText(rawSource));
        }

        return new PluginEntry(rawEntry.trim(), PluginSource.UNKNOWN);
    }

    /**
     * Ensures blacklist file exists and has starter comments for first-time setup.
     */
    private void ensureBlacklistFileExists()
    {
        final Path blacklistPath = getBlacklistFilePath();
        if (Files.exists(blacklistPath))
        {
            return;
        }

        try
        {
            Files.createDirectories(blacklistPath.getParent());
            final List<String> starter = List.of(
                "# RFL blacklist entries",
                "# One entry per line, or comma-separated values.",
                "# Lines starting with # are ignored.",
                "example banned plugin"
            );
            Files.write(
                blacklistPath,
                starter,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
        }
        catch (IOException ex)
        {
            // Keep plugin usable even if file bootstrap fails; loader handles missing file gracefully.
        }
    }

    /**
     * Loads blacklist entries from local file, reloading only when file timestamp changes.
     *
     * @return normalized lowercase blacklist phrases
     */
    private Set<String> loadBlacklistEntriesFromFile()
    {
        final Path blacklistPath = getBlacklistFilePath();
        if (!Files.exists(blacklistPath))
        {
            ensureBlacklistFileExists();
        }

        try
        {
            final long lastModified = Files.exists(blacklistPath)
                ? Files.getLastModifiedTime(blacklistPath).toMillis()
                : -1L;
            if (lastModified == cachedBlacklistLastModifiedMillis)
            {
                return Collections.unmodifiableSet(cachedBlacklistEntries);
            }

            final Set<String> parsed = new LinkedHashSet<>();
            if (Files.exists(blacklistPath))
            {
                final List<String> lines = Files.readAllLines(blacklistPath, StandardCharsets.UTF_8);
                for (String rawLine : lines)
                {
                    final String withoutComment = rawLine.split("#", 2)[0];
                    final String[] tokens = withoutComment.split(",");
                    for (String token : tokens)
                    {
                        final String normalized = token.trim().toLowerCase(Locale.ENGLISH);
                        if (!normalized.isEmpty())
                        {
                            parsed.add(normalized);
                        }
                    }
                }
            }

            cachedBlacklistEntries.clear();
            cachedBlacklistEntries.addAll(parsed);
            cachedBlacklistLastModifiedMillis = lastModified;
            return Collections.unmodifiableSet(cachedBlacklistEntries);
        }
        catch (IOException ex)
        {
            return Collections.unmodifiableSet(cachedBlacklistEntries);
        }
    }

    /**
     * Returns path to local blacklist configuration file.
     *
     * @return blacklist file path
     */
    private Path getBlacklistFilePath()
    {
        return getSessionLogDirectory().resolve(BLACKLIST_FILE_NAME);
    }

    /**
     * Hashes plugin entry list in stable sorted order for session record storage.
     *
     * @param entries plugin entries to hash
     * @return SHA-256 hash string for the plugin list snapshot
     */
    private String hashPluginEntryList(final List<PluginEntry> entries)
    {
        final StringBuilder builder = new StringBuilder();
        for (PluginEntry entry : entries)
        {
            builder.append(entry.name).append('|').append(entry.source.name()).append('\n');
        }
        return sha256Hex(builder.toString());
    }

    /**
     * Returns session log directory path.
     *
     * @return path where audit logs are written
     */
    private Path getSessionLogDirectory()
    {
        final String home = System.getProperty("user.home");
        return Path.of(home, ".runelite", "rfl-audit");
    }

    /**
     * Sanitizes user-provided match id for safe use in log file names.
     *
     * @param requestedMatchId raw match id text
     * @return sanitized match id string
     */
    private String sanitizeMatchId(final String requestedMatchId)
    {
        final String raw = requestedMatchId == null ? "" : requestedMatchId.trim();
        final String fallback = raw.isEmpty() ? "match" : raw;
        final String sanitized = fallback.replaceAll("[^a-zA-Z0-9-_]", "_");
        return sanitized.isEmpty() ? "match" : sanitized;
    }

    /**
     * Parses an integer safely from string value.
     *
     * @param raw numeric text
     * @return parsed integer or zero on parse error
     */
    private int parseIntSafe(final String raw)
    {
        try
        {
            return Integer.parseInt(raw);
        }
        catch (NumberFormatException ignored)
        {
            return 0;
        }
    }

    /**
     * Encodes text as Base64 for delimiter-safe storage inside log lines.
     *
     * @param value raw text value
     * @return Base64 encoded text
     */
    private String base64(final String value)
    {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Computes SHA-256 hash for the provided text.
     *
     * @param value text to hash
     * @return lowercase SHA-256 hex string
     */
    private String sha256Hex(final String value)
    {
        try
        {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            final StringBuilder builder = new StringBuilder();
            for (byte b : hash)
            {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        }
        catch (NoSuchAlgorithmException ex)
        {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /**
     * Determines whether a plugin is enabled using PluginManager capability checks.
     *
     * @param plugin plugin instance to check
     * @return true when plugin appears enabled
     */
    private boolean isPluginEnabled(final Plugin plugin)
    {
        try
        {
            final Object result = pluginManager.getClass()
                .getMethod("isPluginEnabled", Plugin.class)
                .invoke(pluginManager, plugin);
            if (result instanceof Boolean)
            {
                return (Boolean) result;
            }
        }
        catch (ReflectiveOperationException ignored)
        {
            // Fall through to default behavior when method availability changes across client versions.
        }
        return true;
    }

    /**
     * Resolves display name for a plugin, preferring the descriptor annotation name.
     *
     * @param plugin plugin instance
     * @return display-ready plugin name
     */
    private String pluginDisplayName(final Plugin plugin)
    {
        final PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
        if (descriptor != null && descriptor.name() != null && !descriptor.name().trim().isEmpty())
        {
            return descriptor.name().trim();
        }
        return plugin.getClass().getSimpleName();
    }

    /**
     * Classifies plugin source into built-in, plugin-hub, unofficial, or unknown.
     *
     * @param plugin plugin instance
     * @return source classification
     */
    private PluginSource pluginSource(final Plugin plugin)
    {
        final String className = plugin.getClass().getName();
        if (className.startsWith("net.runelite.client.plugins."))
        {
            return PluginSource.BUILTIN;
        }

        final URL location = plugin.getClass().getProtectionDomain().getCodeSource() == null
            ? null
            : plugin.getClass().getProtectionDomain().getCodeSource().getLocation();

        final String locationText = location == null ? "" : location.toString().toLowerCase(Locale.ENGLISH);
        if (locationText.contains("plugin-hub") || locationText.contains("pluginhub"))
        {
            return PluginSource.PLUGIN_HUB;
        }
        if (locationText.contains("sideload") || locationText.contains("externalplugin"))
        {
            return PluginSource.UNOFFICIAL;
        }
        if (locationText.isEmpty())
        {
            return PluginSource.UNKNOWN;
        }
        return PluginSource.UNOFFICIAL;
    }

    /**
     * Copies text to system clipboard.
     *
     * @param text text content to copy
     */
    private void copyToClipboard(final String text)
    {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    /**
     * Reads text content from the system clipboard.
     *
     * @return clipboard text when available, otherwise empty string
     */
    private String readClipboardText()
    {
        try
        {
            final Object data = Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .getData(DataFlavor.stringFlavor);
            return data instanceof String ? (String) data : "";
        }
        catch (Exception ex)
        {
            return "";
        }
    }

    /**
     * Updates the side panel with latest summary and details.
     */
    private void refreshPanel()
    {
        SwingUtilities.invokeLater(() -> panel.setResultText(lastSummary, lastDetails));
    }

    /**
     * Returns a safe non-empty text value.
     *
     * @param value input text
     * @return normalized value with fallback
     */
    private String safeText(final String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return "Unknown";
        }
        return value.trim();
    }

    /**
     * Normalizes player name values by removing tags and trimming.
     *
     * @param rawName source player name text
     * @return cleaned player name without color tags
     */
    private String cleanPlayerName(final String rawName)
    {
        if (rawName == null)
        {
            return "";
        }
        return Text.removeTags(rawName).trim();
    }

    /**
     * Builds panel icon image.
     *
     * @return generated icon image
     */
    private static BufferedImage createPanelIcon()
    {
        final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(35, 35, 35));
        graphics.fillRect(0, 0, 16, 16);

        graphics.setColor(new Color(210, 130, 30));
        graphics.fillRect(2, 3, 12, 2);
        graphics.fillRect(2, 7, 12, 2);
        graphics.fillRect(2, 11, 12, 2);

        graphics.setColor(new Color(255, 220, 120));
        graphics.fillRect(2, 3, 2, 2);
        graphics.fillRect(2, 7, 2, 2);
        graphics.fillRect(2, 11, 2, 2);
        graphics.dispose();
        return image;
    }

    /**
     * Holds one plugin entry used in export and analysis flow.
     */
    private static final class PluginEntry
    {
        private final String name;
        private final PluginSource source;

        /**
         * Creates one plugin entry.
         *
         * @param name plugin display name
         * @param source plugin source classification
         */
        private PluginEntry(final String name, final PluginSource source)
        {
            this.name = name;
            this.source = source;
        }
    }

    /**
     * Holds analysis output for summary, detail text, and hit lists.
     */
    private static final class AnalysisResult
    {
        private final String summary;
        private final String details;
        private final List<String> blacklistedPlugins;
        private final List<String> unofficialPlugins;

        /**
         * Creates one analysis result instance.
         *
         * @param summary short result summary
         * @param details multi-line report content
         * @param blacklistedPlugins matched blacklist hits
         * @param unofficialPlugins unofficial plugin hits
         */
        private AnalysisResult(
            final String summary,
            final String details,
            final List<String> blacklistedPlugins,
            final List<String> unofficialPlugins
        )
        {
            this.summary = summary;
            this.details = details;
            this.blacklistedPlugins = blacklistedPlugins;
            this.unofficialPlugins = unofficialPlugins;
        }
    }

    /**
     * Stores one checked player result for quick ref tracking.
     */
    private static final class PlayerCheckRecord
    {
        private final String playerName;
        private final String checkedAt;
        private final String summary;
        private final int blacklistHits;
        private final int unofficialHits;

        /**
         * Creates one player check result record.
         *
         * @param playerName checked player name
         * @param checkedAt timestamp of the check
         * @param summary analysis summary
         * @param blacklistHits number of blacklist hits
         * @param unofficialHits number of unofficial hits
         */
        private PlayerCheckRecord(
            final String playerName,
            final String checkedAt,
            final String summary,
            final int blacklistHits,
            final int unofficialHits
        )
        {
            this.playerName = playerName;
            this.checkedAt = checkedAt;
            this.summary = summary;
            this.blacklistHits = blacklistHits;
            this.unofficialHits = unofficialHits;
        }
    }

    /**
     * Enum representing source categories used in exports and checks.
     */
    private enum PluginSource
    {
        BUILTIN,
        PLUGIN_HUB,
        UNOFFICIAL,
        UNKNOWN;

        /**
         * Parses source text from export lines.
         *
         * @param value source text token
         * @return parsed source enum value
         */
        private static PluginSource fromText(final String value)
        {
            for (PluginSource source : values())
            {
                if (source.name().equalsIgnoreCase(value))
                {
                    return source;
                }
            }
            return UNKNOWN;
        }
    }
}
