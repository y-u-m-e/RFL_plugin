package com.rfl.log;

import sh.yumekui.toolkit.text.PlayerNames;
import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.api.Client;
import net.runelite.api.Player;

/**
 * What the league refs collect from each player: this client's own plugin list on entering and
 * leaving a player-owned house, and a line for each of its plugins turned on or off while logged
 * in, written to the local day file by {@link PluginLog}. Only this client's plugins are read; no
 * other player's are known.
 *
 * <p>Threads: client thread only.
 */
@Singleton
public final class PluginAudit
{
    /** The snapshot's {@code event} on entering a house. */
    static final String ENTER = "enter";
    /** The snapshot's {@code event} on leaving one. */
    static final String LEAVE = "leave";

    private final Client client;
    private final PluginLog log;
    private final PluginSnapshotter snapshotter;

    @Inject
    PluginAudit(Client client, PluginLog log, PluginSnapshotter snapshotter)
    {
        this.client = client;
        this.log = log;
        this.snapshotter = snapshotter;
    }

    /** A house was entered or left: snapshots the plugin list, whether or not anything was toggled. */
    public void onPohChanged(boolean inPoh)
    {
        log.snapshot(System.currentTimeMillis(), localName(), client.getWorld(), snapshotter.snapshot(),
            inPoh ? ENTER : LEAVE);
    }

    /** One of this client's plugins was turned on or off while logged in. */
    public void onPluginToggled(String name, boolean enabled)
    {
        log.toggle(System.currentTimeMillis(), localName(), client.getWorld(), name, enabled);
    }

    private String localName()
    {
        Player local = client.getLocalPlayer();
        return PlayerNames.sanitized(local);
    }
}
