package com.rfl.teams;

import sh.yumekui.toolkit.text.PlayerNames;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * The right-click team entries on a player inside a house: "RFL: Team A", "RFL: Team B" and
 * "RFL: Unassign", only the ones that change something, once per player per menu. The option
 * text itself carries no colour tags, so the client's normal hover highlight on the menu row
 * still works; instead the team colour is on the target (the player's name) for Team A and
 * Team B, and "Unassign" leaves the target exactly as the client gave it. They are added as
 * {@link MenuAction#RUNELITE_PLAYER} entries beside the game's own, never replacing or
 * reordering them, and a click only sets a local team label ({@link Teams}).
 *
 * <p>Threads: client thread only, like menu events.
 */
@Singleton
public final class TeamMenu
{
    /** Every entry this menu adds starts with this, so a second player option can tell it is there. */
    private static final String PREFIX = "RFL: ";
    /**
     * {@code Menu#createMenuEntry} index: negative counts from the end, and -1 appends after the
     * existing entries, which the client draws at the top of the menu.
     */
    private static final int APPEND = -1;

    private final Client client;
    private final Teams teams;

    @Inject
    TeamMenu(Client client, Teams teams)
    {
        this.client = client;
        this.teams = teams;
    }

    /** Adds the team entries for the player whose option was just added, when in a house. */
    public void onMenuEntryAdded(MenuEntryAdded event, boolean inPoh)
    {
        MenuEntry entry = event.getMenuEntry();
        Player player = entry.getPlayer();
        String name = PlayerNames.sanitized(player);
        List<Teams.Team> choices = Teams.menuChoices(inPoh, isPlayerOption(entry.getType()) && name != null,
            teams.team(name));
        if (choices.isEmpty() || alreadyAdded(entry.getIdentifier()))
        {
            return;
        }
        for (Teams.Team team : choices)
        {
            client.getMenu().createMenuEntry(APPEND)
                .setOption(Teams.menuLabel(team))
                .setTarget(coloredTarget(entry.getTarget(), team))
                .setType(MenuAction.RUNELITE_PLAYER)
                .setIdentifier(entry.getIdentifier())
                .onClick(clicked -> teams.assign(name, team));
        }
    }

    /**
     * The menu target for a team entry: for Team A / Team B, {@code target} (the player's name,
     * as the client gave it) with any existing tags stripped and the team's colour wrapped back
     * on; for Unassign, {@code target} untouched.
     */
    static String coloredTarget(String target, Teams.Team team)
    {
        return team == null ? target : ColorUtil.wrapWithColorTag(Text.removeTags(target), team.color());
    }

    /**
     * Whether this player's entries are in the menu already: a player's menu has several player
     * options, each firing the event, and the entries go in once.
     */
    private boolean alreadyAdded(int playerIdentifier)
    {
        for (MenuEntry existing : client.getMenu().getMenuEntries())
        {
            if (existing.getType() == MenuAction.RUNELITE_PLAYER && existing.getIdentifier() == playerIdentifier
                && Text.removeTags(existing.getOption()).startsWith(PREFIX))
            {
                return true;
            }
        }
        return false;
    }

    /** The menu actions a player in the scene offers (Follow, Trade with, ...). */
    static boolean isPlayerOption(MenuAction type)
    {
        if (type == null)
        {
            return false;
        }
        switch (type)
        {
            case PLAYER_FIRST_OPTION:
            case PLAYER_SECOND_OPTION:
            case PLAYER_THIRD_OPTION:
            case PLAYER_FOURTH_OPTION:
            case PLAYER_FIFTH_OPTION:
            case PLAYER_SIXTH_OPTION:
            case PLAYER_SEVENTH_OPTION:
            case PLAYER_EIGHTH_OPTION:
                return true;
            default:
                return false;
        }
    }
}
