package com.rfl;

import java.util.Collections;
import java.util.List;

/**
 * Wire report body POJO for {@code POST /plugins/rfl/report} (spec §4). Field names are the
 * plain Java field names on purpose: Gson's default field naming already matches the spec's
 * JSON keys, so no {@code @SerializedName} is needed.
 *
 * <p>Self only: the only player name in a report is {@code rsn}, the local player's own. Contact
 * events identify the other body by a per-client {@code contactId}, never by name.
 */
final class RflReport
{
    static final int VERSION = 1;
    static final String PLUGIN_VERSION = "1.0.0";

    final int v = VERSION;
    final String rsn;
    final String installId;
    final String pluginVersion = PLUGIN_VERSION;
    final int world;
    final long sentAt;
    final boolean inPoh;
    /** The game the player is currently in, from the retired game browser; "" when none. */
    final String gameId;
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

        Features(final boolean plugins, final boolean contacts)
        {
            this.plugins = plugins;
            this.contacts = contacts;
        }
    }

    RflReport(final String rsn, final String installId, final int world, final long sentAt,
        final boolean inPoh, final String gameId, final List<PluginEntry> plugins, final List<RflEvent> events, final Features features)
    {
        this.rsn = rsn;
        this.installId = installId;
        this.world = world;
        this.sentAt = sentAt;
        this.inPoh = inPoh;
        this.gameId = gameId == null ? "" : gameId;
        this.plugins = features.plugins ? plugins : Collections.emptyList();
        this.events = events;
        this.features = features;
    }
}
