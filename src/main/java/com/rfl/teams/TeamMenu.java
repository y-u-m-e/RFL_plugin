package com.rfl.teams;

import sh.yumekui.toolkit.text.PlayerNames;
import java.awt.Color;
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
 * "RFL: Unassign", only the ones that change something, once per player per menu. The "RFL: "
 * prefix is always grey, and only the team name is in the team's colour; "Unassign" keeps the
 * menu's default text colour. They are added as {@link MenuAction#RUNELITE_PLAYER} entries
 * beside the game's own, never replacing or reordering them, and a click only sets a local team
 * label ({@link Teams}).
 *
 * <p>Threads: client thread only, like menu events.
 */
@Singleton
public final class TeamMenu
{
    /** Every entry this menu adds starts with this, so a second player option can tell it is there. */
    private static final String PREFIX = "RFL: ";
    /**
     * The "RFL: " prefix colour: light enough to read on the OSRS menu's dark background, but
     * clearly not the team colour or the menu's default white/yellow text.
     */
    private static final Color PREFIX_COLOR = new Color(0x9f, 0x9f, 0x9f);
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
                .setOption(coloredOption(team))
                .setTarget(entry.getTarget())
                .setType(MenuAction.RUNELITE_PLAYER)
                .setIdentifier(entry.getIdentifier())
                .onClick(clicked -> teams.assign(name, team));
        }
    }

    /**
     * "RFL: Team A" / "RFL: Team B" / "RFL: Unassign", with the "RFL: " prefix always grey and
     * only the team name in the team's colour; "Unassign" is left in the menu's default colour.
     */
    static String coloredOption(Teams.Team team)
    {
        String prefix = ColorUtil.wrapWithColorTag(PREFIX, PREFIX_COLOR);
        return prefix + (team == null ? "Unassign" : ColorUtil.wrapWithColorTag(team.label(), team.color()));
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
