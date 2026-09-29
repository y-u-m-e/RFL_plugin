package com.rfl;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Wire report body POJO for {@code POST /plugins/rfl/report} (spec §4). Field names are the
 * plain Java field names on purpose: Gson's default field naming already matches the spec's
 * JSON keys, so no {@code @SerializedName} is needed.
 */
final class RflReport
{
    static final int VERSION = 1;
    static final String PLUGIN_VERSION = "1.0.0";
    /** Max length of the player-typed {@code matchCode} and {@code team} labels. */
    static final int MAX_LABEL = 32;

    final int v = VERSION;
    final String rsn;
    final String installId;
    final String pluginVersion = PLUGIN_VERSION;
    final int world;
    final long sentAt;
    final boolean inPoh;
    final String matchCode;
    final String team;
    final List<String> seen;
    final List<PluginEntry> plugins;
    final List<RflEvent> events;
    final Features features;

    /**
     * Which optional features are on (true = enabled). Sent every report so the server can flag
     * any that are off; a disabled feature's field is blanked here, not left out.
     */
    static final class Features
    {
        final boolean plugins;
        final boolean contacts;
        final boolean nearby;

        Features(final boolean plugins, final boolean contacts, final boolean nearby)
        {
            this.plugins = plugins;
            this.contacts = contacts;
            this.nearby = nearby;
        }
    }

    RflReport(final String rsn, final String installId, final int world, final long sentAt,
        final boolean inPoh, final String matchCode, final String team, final List<String> seen,
        final List<PluginEntry> plugins, final List<RflEvent> events, final Features features)
    {
        this.rsn = rsn;
        this.installId = installId;
        this.world = world;
        this.sentAt = sentAt;
        this.inPoh = inPoh;
        this.matchCode = label(matchCode, true);
        this.team = label(team, false);
        this.seen = features.nearby ? seen : Collections.emptyList();
        this.plugins = features.plugins ? plugins : Collections.emptyList();
        this.events = events;
        this.features = features;
    }

    /**
     * Normalizes a player-typed label the same way the server does: trimmed, optionally
     * uppercased, capped at {@link #MAX_LABEL} characters. {@code null} becomes empty.
     *
     * @param raw config value as typed
     * @param upper true to uppercase (match codes)
     * @return normalized label, never null
     */
    static String label(final String raw, final boolean upper)
    {
        if (raw == null)
        {
            return "";
        }
        String value = raw.trim();
        if (upper)
        {
            value = value.toUpperCase(Locale.ROOT);
        }
        return value.length() > MAX_LABEL ? value.substring(0, MAX_LABEL) : value;
    }
}
