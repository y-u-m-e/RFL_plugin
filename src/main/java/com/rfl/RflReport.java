package com.rfl;

import java.util.Collections;
import java.util.List;

/**
 * Wire report body POJO for {@code POST /plugins/rfl/report} (spec §4). Field names are the
 * plain Java field names on purpose: Gson's default field naming already matches the spec's
 * JSON keys, so no {@code @SerializedName} is needed.
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
        final boolean inPoh, final List<String> seen, final List<PluginEntry> plugins,
        final List<RflEvent> events, final Features features)
    {
        this.rsn = rsn;
        this.installId = installId;
        this.world = world;
        this.sentAt = sentAt;
        this.inPoh = inPoh;
        this.seen = features.nearby ? seen : Collections.emptyList();
        this.plugins = features.plugins ? plugins : Collections.emptyList();
        this.events = events;
        this.features = features;
    }
}
