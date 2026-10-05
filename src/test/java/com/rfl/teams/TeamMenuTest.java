package com.rfl.teams;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.Before;
import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * {@link TeamMenu}: the right-click "RFL: Team A / Team B / Unassign" entries.
 *
 * <p>M12 (the review finding this guards): a single right-click builds its menu through several
 * {@code MenuEntryAdded} events for the same player (one per base option such as "Follow" or
 * "Trade with"), and above about 50 fps the client rebuilds that live menu from scratch many
 * times a second even while nothing is open, to know the current default left-click action. A
 * dedupe keyed on a remembered player identifier plus the game cycle or tick is too coarse: a
 * tick spans many of those rebuilds, so after the first rebuild in a tick it wrongly believes the
 * entries are "already added" and skips them on every later rebuild in the same tick, even though
 * the live menu was actually cleared and no longer has them. The fix here never remembers
 * anything across a call: {@link TeamMenu#onMenuEntryAdded} dedupes by looking at the live
 * {@link Client#getMenu()} itself, so it is exactly as current as the menu it writes into,
 * whatever the frame rate.
 */
public class TeamMenuTest
{
    private static final Gson GSON = new GsonBuilder().create();

    private FakeMenu menu;
    private Teams teams;
    private TeamMenu teamMenu;

    @Before
    public void setUp()
    {
        menu = new FakeMenu();
        teams = new Teams(GSON, new MemoryStore());
        teamMenu = new TeamMenu(fakeClient(menu), teams);
    }

    /**
     * A real right-click fires {@code MenuEntryAdded} once per base option the player already
     * has (Follow, Trade with, Report, ...), all for the same player identifier, in one build.
     * The team entries must land exactly once for that build, not once per base option.
     */
    @Test
    public void addsTheTeamEntriesOnceNoMatterHowManyPlayerOptionsFireForTheSamePlayer()
    {
        Player bob = fakePlayer("Bob");
        fireBaseOption(bob, 5, "Follow");
        fireBaseOption(bob, 5, "Trade with");
        fireBaseOption(bob, 5, "Report");

        List<MenuEntry> rfl = rflEntries();
        assertEquals("one Team A and one Team B, not three of each", 2, rfl.size());
        assertEquals("3 base options plus the 2 team entries, once", 5, menu.entries.size());
        assertEquals("RFL: Team A", Text.removeTags(rfl.get(0).getOption()));
        assertEquals("RFL: Team B", Text.removeTags(rfl.get(1).getOption()));
    }

    /**
     * Root cause of M12: when the live menu is cleared and rebuilt (what happens many times a
     * second above 50 fps even with nothing open), the team entries must be added again. A
     * dedupe keyed on a remembered tick or identifier would wrongly skip this and drop them from
     * whichever rebuild happens to be the one the player actually sees.
     */
    @Test
    public void rebuildingTheLiveMenuAddsTheEntriesAgainInsteadOfDroppingThem()
    {
        Player bob = fakePlayer("Bob");
        fireBaseOption(bob, 5, "Follow");
        assertEquals(2, rflEntries().size());

        // The client clears and rebuilds the live menu for the next frame, as it does
        // continuously to track the default left-click action, independent of any tick.
        menu.setMenuEntries(new MenuEntry[0]);
        assertEquals("the rebuild really did clear the live menu", 0, menu.entries.size());

        fireBaseOption(bob, 5, "Follow");
        assertEquals("added again after the rebuild, not skipped as 'already added'", 2, rflEntries().size());
    }

    @Test
    public void addsNothingOutsideAHouse()
    {
        fireBaseOption(fakePlayer("Bob"), 5, "Follow", false);
        assertTrue(rflEntries().isEmpty());
    }

    @Test
    public void addsNothingForANonPlayerMenuOption()
    {
        FakeMenuEntry entry = new FakeMenuEntry();
        entry.option = "Attack";
        entry.target = "Goblin";
        entry.identifier = 9;
        entry.type = MenuAction.NPC_FIRST_OPTION;
        entry.player = null;
        menu.entries.add(entry);
        teamMenu.onMenuEntryAdded(new MenuEntryAdded(entry), true);
        assertTrue(rflEntries().isEmpty());
    }

    @Test
    public void addsNothingWhenThePlayerHasNoName()
    {
        fireBaseOption(fakePlayer(null), 5, "Follow");
        assertTrue(rflEntries().isEmpty());
    }

    @Test
    public void onlyOffersTheChoicesThatChangeSomething()
    {
        teams.assign("Bob", Teams.Team.A);
        fireBaseOption(fakePlayer("Bob"), 5, "Follow");

        List<MenuEntry> rfl = rflEntries();
        assertEquals("Team B and Unassign, not Team A again", 2, rfl.size());
        assertEquals("RFL: Team B", Text.removeTags(rfl.get(0).getOption()));
        assertEquals("RFL: Unassign", Text.removeTags(rfl.get(1).getOption()));
    }

    @Test
    public void clickingAnEntryAssignsThatTeam()
    {
        fireBaseOption(fakePlayer("Bob"), 5, "Follow");
        List<MenuEntry> rfl = rflEntries();

        rfl.get(0).onClick().accept(rfl.get(0)); // "RFL: Team A"
        assertEquals(Teams.Team.A, teams.team("Bob"));

        rfl.get(1).onClick().accept(rfl.get(1)); // "RFL: Team B"
        assertEquals(Teams.Team.B, teams.team("Bob"));
    }

    @Test
    public void onlyTheTeamNameIsColouredAndThePrefixIsGreyAndUnassignStaysDefault()
    {
        String a = TeamMenu.coloredOption(Teams.Team.A);
        String b = TeamMenu.coloredOption(Teams.Team.B);
        String unassign = TeamMenu.coloredOption(null);

        assertEquals(ColorUtil.wrapWithColorTag("RFL: ", new java.awt.Color(0x9f, 0x9f, 0x9f))
            + ColorUtil.wrapWithColorTag("Team A", Teams.TEAM_A_COLOR), a);
        assertEquals(ColorUtil.wrapWithColorTag("RFL: ", new java.awt.Color(0x9f, 0x9f, 0x9f))
            + ColorUtil.wrapWithColorTag("Team B", Teams.TEAM_B_COLOR), b);
        assertEquals(ColorUtil.wrapWithColorTag("RFL: ", new java.awt.Color(0x9f, 0x9f, 0x9f)) + "Unassign", unassign);

        // "Unassign" itself is never wrapped in a colour tag: only the grey prefix is tagged.
        assertFalse(unassign.substring(unassign.indexOf("Unassign")).contains("<col="));
        assertEquals("RFL: Team A", Text.removeTags(a));
        assertEquals("RFL: Team B", Text.removeTags(b));
        assertEquals("RFL: Unassign", Text.removeTags(unassign));
    }

    // -- helpers --------------------------------------------------------------------------------

    private void fireBaseOption(Player player, int identifier, String option)
    {
        fireBaseOption(player, identifier, option, true);
    }

    private void fireBaseOption(Player player, int identifier, String option, boolean inPoh)
    {
        FakeMenuEntry entry = new FakeMenuEntry();
        entry.option = option;
        entry.target = player == null ? null : player.getName();
        entry.identifier = identifier;
        entry.type = MenuAction.PLAYER_FIRST_OPTION;
        entry.player = player;
        menu.entries.add(entry);
        teamMenu.onMenuEntryAdded(new MenuEntryAdded(entry), inPoh);
    }

    private List<MenuEntry> rflEntries()
    {
        List<MenuEntry> out = new ArrayList<>();
        for (MenuEntry entry : menu.entries)
        {
            if (entry.getType() == MenuAction.RUNELITE_PLAYER)
            {
                out.add(entry);
            }
        }
        return out;
    }

    private static Player fakePlayer(String name)
    {
        return (Player) Proxy.newProxyInstance(TeamMenuTest.class.getClassLoader(), new Class<?>[] { Player.class },
            (proxy, method, args) -> "getName".equals(method.getName()) && method.getParameterCount() == 0
                ? name
                : defaultFor(method.getReturnType()));
    }

    private static Client fakeClient(Menu menu)
    {
        InvocationHandler handler = (proxy, method, args) -> "getMenu".equals(method.getName())
            && method.getParameterCount() == 0 ? menu : defaultFor(method.getReturnType());
        return (Client) Proxy.newProxyInstance(TeamMenuTest.class.getClassLoader(), new Class<?>[] { Client.class },
            handler);
    }

    private static Object defaultFor(Class<?> returnType)
    {
        if (!returnType.isPrimitive() || returnType == void.class)
        {
            return null;
        }
        if (returnType == boolean.class)
        {
            return false;
        }
        if (returnType == char.class)
        {
            return (char) 0;
        }
        if (returnType == double.class)
        {
            return 0d;
        }
        if (returnType == float.class)
        {
            return 0f;
        }
        if (returnType == long.class)
        {
            return 0L;
        }
        return 0;
    }

    /** An in-memory {@link Teams.Store}, like the ConfigManager value. */
    private static final class MemoryStore implements Teams.Store
    {
        String json;

        @Override
        public String load()
        {
            return json;
        }

        @Override
        public void save(String json)
        {
            this.json = json;
        }
    }

    /** A {@link Menu} backed by a plain list, standing in for the client's live menu. */
    private static final class FakeMenu implements Menu
    {
        final List<MenuEntry> entries = new ArrayList<>();

        @Override
        public MenuEntry createMenuEntry(int index)
        {
            FakeMenuEntry entry = new FakeMenuEntry();
            int size = entries.size();
            int pos = index < 0 ? size + index + 1 : index;
            pos = Math.max(0, Math.min(pos, size));
            entries.add(pos, entry);
            return entry;
        }

        @Override
        public MenuEntry[] getMenuEntries()
        {
            return entries.toArray(new MenuEntry[0]);
        }

        @Override
        public void setMenuEntries(MenuEntry[] newEntries)
        {
            entries.clear();
            for (MenuEntry entry : newEntries)
            {
                entries.add(entry);
            }
        }

        @Override
        public void removeMenuEntry(MenuEntry entry)
        {
            entries.remove(entry);
        }

        @Override
        public int getMenuX()
        {
            return 0;
        }

        @Override
        public int getMenuY()
        {
            return 0;
        }

        @Override
        public int getMenuWidth()
        {
            return 0;
        }

        @Override
        public int getMenuHeight()
        {
            return 0;
        }
    }

    /** A plain, settable {@link MenuEntry}; only the fields {@link TeamMenu} touches are live. */
    private static final class FakeMenuEntry implements MenuEntry
    {
        String option;
        String target;
        int identifier;
        MenuAction type;
        Player player;
        Consumer<MenuEntry> onClick;

        @Override
        public String getOption()
        {
            return option;
        }

        @Override
        public MenuEntry setOption(String option)
        {
            this.option = option;
            return this;
        }

        @Override
        public String getTarget()
        {
            return target;
        }

        @Override
        public MenuEntry setTarget(String target)
        {
            this.target = target;
            return this;
        }

        @Override
        public int getIdentifier()
        {
            return identifier;
        }

        @Override
        public MenuEntry setIdentifier(int identifier)
        {
            this.identifier = identifier;
            return this;
        }

        @Override
        public MenuAction getType()
        {
            return type;
        }

        @Override
        public MenuEntry setType(MenuAction type)
        {
            this.type = type;
            return this;
        }

        @Override
        public int getParam0()
        {
            return 0;
        }

        @Override
        public MenuEntry setParam0(int param0)
        {
            return this;
        }

        @Override
        public int getParam1()
        {
            return 0;
        }

        @Override
        public MenuEntry setParam1(int param1)
        {
            return this;
        }

        @Override
        public boolean isForceLeftClick()
        {
            return false;
        }

        @Override
        public MenuEntry setForceLeftClick(boolean forceLeftClick)
        {
            return this;
        }

        @Override
        public int getWorldViewId()
        {
            return 0;
        }

        @Override
        public MenuEntry setWorldViewId(int worldViewId)
        {
            return this;
        }

        @Override
        public boolean isDeprioritized()
        {
            return false;
        }

        @Override
        public MenuEntry setDeprioritized(boolean deprioritized)
        {
            return this;
        }

        @Override
        public MenuEntry onClick(Consumer<MenuEntry> consumer)
        {
            this.onClick = consumer;
            return this;
        }

        @Override
        public Consumer<MenuEntry> onClick()
        {
            return onClick;
        }

        @Override
        public boolean isItemOp()
        {
            return false;
        }

        @Override
        public int getItemOp()
        {
            return 0;
        }

        @Override
        public int getItemId()
        {
            return -1;
        }

        @Override
        public MenuEntry setItemId(int itemId)
        {
            return this;
        }

        @Override
        public net.runelite.api.widgets.Widget getWidget()
        {
            return null;
        }

        @Override
        public net.runelite.api.NPC getNpc()
        {
            return null;
        }

        @Override
        public Player getPlayer()
        {
            return player;
        }

        @Override
        public net.runelite.api.Actor getActor()
        {
            return player;
        }

        @Override
        public Menu getSubMenu()
        {
            return null;
        }

        @Override
        public Menu createSubMenu()
        {
            return null;
        }

        @Override
        public void deleteSubMenu()
        {
        }
    }
}
