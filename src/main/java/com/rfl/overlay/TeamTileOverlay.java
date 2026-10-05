package com.rfl.overlay;

import sh.yumekui.toolkit.overlay.TilePainter;
import sh.yumekui.toolkit.text.PlayerNames;
import com.rfl.panel.RflPanel;
import com.rfl.teams.Teams;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * While the RFL panel is the open sidebar tab and its Teams view is selected, outlines and fills
 * the tile under each player on Team A or Team B in their team colour. Unassigned players get
 * nothing. Display only; models are never tinted. Draws inside and outside a house alike, since
 * assignments are by name.
 *
 * <p>Threads: {@link #setShowing} from the EDT, {@link #render} on the client thread; the flag is
 * volatile. When the Teams view isn't showing, render returns before touching any player.
 */
@Singleton
public final class TeamTileOverlay extends Overlay
{
    /** Fill alpha (0-255) under the team-coloured outline. */
    static final int FILL_ALPHA = 50;
    private static final BasicStroke STROKE = new BasicStroke(2);

    private final Client client;
    private final Teams teams;
    private volatile boolean showing;

    @Inject
    TeamTileOverlay(Client client, Teams teams)
    {
        this.client = client;
        this.teams = teams;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    /** Draw only while the panel is the open sidebar tab and the Teams view is selected. */
    public static boolean active(boolean panelVisible, RflPanel.View view)
    {
        return panelVisible && view == RflPanel.View.TEAMS;
    }

    /** EDT: whether the Teams view is on screen right now ({@link #active}). */
    public void setShowing(boolean showing)
    {
        this.showing = showing;
    }

    /** The team whose tile colour a player gets, or null to skip them (unassigned or nameless). */
    static Teams.Team tileTeam(String name, Function<String, Teams.Team> teamOf)
    {
        return name == null ? null : teamOf.apply(name);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!showing)
        {
            return null;
        }
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null)
        {
            return null;
        }
        for (Player player : worldView.players())
        {
            if (player == null)
            {
                continue;
            }
            Teams.Team team = tileTeam(PlayerNames.sanitized(player), teams::team);
            LocalPoint at = team == null ? null : player.getLocalLocation();
            Polygon tile = at == null ? null : Perspective.getCanvasTilePoly(client, at);
            if (tile == null)
            {
                continue;
            }
            TilePainter.paint(graphics, tile, team.color(), FILL_ALPHA, STROKE);
        }
        return null;
    }
}
