package com.rfl.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** {@link PluginHistory}: today's plugin log as a ref would paste it into Discord. */
public class PluginHistoryTest
{
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final Gson GSON = new GsonBuilder().create();
    /** 2026-10-03 12:00:00 UTC. */
    private static final long T = LocalDateTime.of(2026, 10, 3, 12, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
    private static final long MIN = 60_000L;

    private static List<PluginEntry> plugins(String... onNames)
    {
        List<PluginEntry> list = new ArrayList<>();
        list.add(new PluginEntry("Zoom", false, PluginEntry.BUILTIN));
        for (String name : onNames)
        {
            list.add(new PluginEntry(name, true, name.startsWith("Side") ? PluginEntry.SIDELOADED : PluginEntry.HUB));
        }
        return list;
    }

    private static String enter(long at, String... on)
    {
        return GSON.toJson(new PluginLog.Snapshot(at, "Ref Bob", 354, plugins(on), "enter"));
    }

    private static String leave(long at, String... on)
    {
        return GSON.toJson(new PluginLog.Snapshot(at, "Ref Bob", 354, plugins(on), "leave"));
    }

    private static String toggle(long at, String name, boolean on)
    {
        return GSON.toJson(new PluginLog.Toggle(at, "Ref Bob", 354, name, on));
    }

    @Test
    public void groupsByVisitNewestFirstWithAlphabeticalListsAndTimedToggles()
    {
        List<String> lines = List.of(
            enter(T, "Agility", "boosts", "Ground Items"),
            toggle(T + MIN, "Block Tracker", true),
            toggle(T + 2 * MIN, "Block Tracker", false),
            leave(T + 10 * MIN, "Agility", "boosts", "Ground Items"),
            enter(T + 20 * MIN, "Agility", "Ground Items"),
            leave(T + 30 * MIN, "Agility", "Ground Items"));
        PluginHistory.Result r = PluginHistory.format(GSON, lines, UTC, PluginHistory.DISCORD_LIMIT);
        assertEquals("```\n"
            + "RFL plugin history - Ref Bob - W354\n"
            + "2026-10-03 12:00:00 to 12:30:00 - 2 house visits\n"
            + "\n"
            + "Visit 12:20:00 to 12:30:00 (W354)\n"
            + "On (2): Agility, Ground Items\n"
            + "\n"
            + "Visit 12:00:00 to 12:10:00 (W354)\n"
            + "On (3): Agility, boosts, Ground Items\n"
            + "  +Block Tracker 12:01:00\n"
            + "  -Block Tracker 12:02:00\n"
            + "```", r.getText());
        assertEquals(2, r.getVisits());
        assertEquals(2, r.getShown());
        assertEquals("Copied 2 visits (" + r.getText().length() + " chars)", PluginHistory.copiedMessage(r));
    }

    @Test
    public void anUnchangedListSaysSameAsTheVisitBelow()
    {
        List<String> lines = List.of(enter(T, "Agility"), leave(T + MIN, "Agility"), enter(T + 2 * MIN, "Agility"));
        String text = PluginHistory.format(GSON, lines, UTC, PluginHistory.DISCORD_LIMIT).getText();
        assertTrue(text, text.contains("Visit 12:02:00 to now (W354)\nOn: same as the visit below\n"));
        assertTrue(text, text.contains("Visit 12:00:00 to 12:01:00 (W354)\nOn (1): Agility\n"));
    }

    @Test
    public void aVisitBegunBeforeTodaysFileHasAnUnknownStartAndSideloadedIsMarked()
    {
        List<String> lines = List.of(leave(T, "Agility", "Side Thing"));
        String text = PluginHistory.format(GSON, lines, UTC, PluginHistory.DISCORD_LIMIT).getText();
        assertTrue(text, text.contains("* sideloaded plugin\n"));
        assertTrue(text, text.contains("Visit ? to 12:00:00 (W354)\nOn (2): Agility, Side Thing*\n"));
    }

    @Test
    public void longHistoryKeepsTheNewestVisitsUnderTheLimitAndCountsTheRest()
    {
        String[] many = new String[40];
        for (int i = 0; i < many.length; i++)
        {
            many[i] = "Plugin number " + i;
        }
        List<String> lines = new ArrayList<>();
        for (int v = 0; v < 12; v++)
        {
            // A different list each visit, so none collapses to "same as the visit below".
            String[] on = java.util.Arrays.copyOf(many, many.length - v);
            lines.add(enter(T + v * 10 * MIN, on));
            lines.add(toggle(T + v * 10 * MIN + MIN, "Block Tracker", v % 2 == 0));
            lines.add(leave(T + v * 10 * MIN + 5 * MIN, on));
        }
        PluginHistory.Result r = PluginHistory.format(GSON, lines, UTC, PluginHistory.DISCORD_LIMIT);
        assertTrue("length " + r.getText().length(), r.getText().length() <= PluginHistory.DISCORD_LIMIT);
        assertEquals(12, r.getVisits());
        assertTrue(r.getShown() >= 1 && r.getShown() < 12);
        int newest = r.getText().indexOf("Visit 13:50:00");
        int older = r.getText().indexOf("Visit 13:40:00");
        assertTrue("newest first", newest > 0 && (older < 0 || older > newest));
        assertTrue(r.getText(), r.getText().contains("… " + (12 - r.getShown()) + " more visits (older, not shown)"));
        assertEquals("Copied " + r.getShown() + " of 12 visits (" + String.format(java.util.Locale.US, "%,d",
            r.getText().length()) + " chars)", PluginHistory.copiedMessage(r));
    }

    @Test
    public void theNewestVisitIsKeptWholeEvenOverTheLimit()
    {
        List<String> lines = List.of(enter(T, "Agility", "Ground Items"), enter(T + MIN, "Agility"));
        PluginHistory.Result r = PluginHistory.format(GSON, lines, UTC, 10);
        assertEquals(1, r.getShown());
        assertTrue(r.getText().contains("Visit 12:01:00 to now"));
        assertTrue(r.getText().contains("… 1 more visit (older, not shown)"));
    }

    @Test
    public void togglesOutsideAHouseAreCountedAndTornLinesSkipped()
    {
        List<String> lines = List.of(toggle(T, "Agility", true), "{torn", "", "[1,2]", enter(T + MIN, "Agility"),
            leave(T + 2 * MIN, "Agility"), toggle(T + 3 * MIN, "Agility", false));
        PluginHistory.Result r = PluginHistory.format(GSON, lines, UTC, PluginHistory.DISCORD_LIMIT);
        assertEquals(1, r.getVisits());
        assertTrue(r.getText(), r.getText().contains("2 plugin toggles outside a house (full list in rfl/plugins)"));
        assertTrue(r.getText(), r.getText().startsWith("```\nRFL plugin history - Ref Bob - W354\n"
            + "2026-10-03 12:00:00 to 12:03:00 - 1 house visit\n"));
    }

    @Test
    public void nothingToCopyWithoutSnapshotsOrToggles()
    {
        assertNull(PluginHistory.format(GSON, List.of(), UTC, PluginHistory.DISCORD_LIMIT).getText());
        assertNull(PluginHistory.format(GSON, List.of("{torn", "{\"type\":\"other\",\"timeMs\":1}"), UTC,
            PluginHistory.DISCORD_LIMIT).getText());
        PluginHistory.Result outsideOnly = PluginHistory.format(GSON, List.of(toggle(T, "Agility", true)), UTC,
            PluginHistory.DISCORD_LIMIT);
        assertEquals(0, outsideOnly.getVisits());
        assertTrue(PluginHistory.copiedMessage(outsideOnly).startsWith("Copied: no house visits today ("));
    }

    @Test
    public void charCountUsesThousandsSeparators()
    {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1240; i++)
        {
            big.append('x');
        }
        assertEquals("Copied 3 visits (1,240 chars)",
            PluginHistory.copiedMessage(new PluginHistory.Result(big.toString(), 3, 3)));
    }
}
