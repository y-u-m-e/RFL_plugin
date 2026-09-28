package com.rfl;

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

    RflReport(final String rsn, final String installId, final int world, final long sentAt,
        final boolean inPoh, final List<String> seen, final List<PluginEntry> plugins,
        final List<RflEvent> events)
    {
        this.rsn = rsn;
        this.installId = installId;
        this.world = world;
        this.sentAt = sentAt;
        this.inPoh = inPoh;
        this.seen = seen;
        this.plugins = plugins;
        this.events = events;
    }
}
