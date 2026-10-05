package com.rfl.replay;

import com.rfl.RflConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.Test;

/** {@link ReplayFileName}: the Replay file name template, expanded and made safe. */
public class ReplayFileNameTest
{
    private static final ZoneId UTC = ZoneOffset.UTC;
    /** 2026-10-03 11:41:12 UTC. */
    private static final long T = LocalDateTime.of(2026, 10, 3, 11, 41, 12).toInstant(ZoneOffset.UTC).toEpochMilli();

    private static String name(String template, String player)
    {
        return ReplayFileName.fileName(template, T, UTC, 354, player);
    }

    @Test
    public void defaultReproducesTheOriginalName()
    {
        assertEquals("2026-10-03_114112_w354.rflr.gz", name(ReplayFileName.DEFAULT_TEMPLATE, "Ref Bob"));
        String setting = new RflConfig()
        {
        }.replayFileName();
        assertEquals("the setting's default", "2026-10-03_114112_w354.rflr.gz", name(setting, "Ref Bob"));
    }

    @Test
    public void everyTokenExpands()
    {
        assertEquals("Ref Bob w354 2026-10-03 114112.rflr.gz", name("{player} w{world} {date} {time}", "Ref Bob"));
        assertEquals("game-unknown.rflr.gz", name("game-{player}", null));
        assertEquals("plain.rflr.gz", name("plain", null));
    }

    @Test
    public void emptyOrInvalidTemplatesFallBackToTheDefault()
    {
        String fallback = "2026-10-03_114112_w354.rflr.gz";
        assertEquals(fallback, name(null, "Bob"));
        assertEquals(fallback, name("", "Bob"));
        assertEquals(fallback, name("   ", "Bob"));
        assertEquals("unknown token", fallback, name("{date}_{nope}", "Bob"));
        assertEquals("unclosed brace", fallback, name("{date", "Bob"));
        assertEquals("stray brace", fallback, name("date}", "Bob"));
        assertEquals("nothing safe left", fallback, name(" ... ", "Bob"));
    }

    @Test
    public void sanitisingRemovesSeparatorsAndWindowsForbiddenCharacters()
    {
        assertEquals("a_b_c_d_e_f_g_h_i_j.rflr.gz", name("a/b\\c:d*e?f\"g<h>i|j", null));
        assertEquals("tab_new_line.rflr.gz", name("tab\tnew\nline", null));
        assertEquals("no traversal", "_evil.rflr.gz", name("../evil", null));
        assertEquals("_evil.rflr.gz", name("..\\evil", null));
        assertEquals("trimmed, trailing dots dropped", "x.rflr.gz", name("  x. . ", null));
        assertEquals("a player name with a slash", "w354 A_B.rflr.gz", name("w{world} {player}", "A/B"));
    }

    @Test
    public void reservedWindowsNamesGetAPrefix()
    {
        assertEquals("_CON.rflr.gz", name("CON", null));
        assertEquals("_nul.rflr.gz", name("nul", null));
        assertEquals("_com1.rflr.gz", name("com1", null));
        assertEquals("CONSOLE.rflr.gz", name("CONSOLE", null));
    }

    @Test
    public void lengthIsCapped()
    {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++)
        {
            longName.append('a');
        }
        String out = name(longName.toString(), null);
        assertEquals(ReplayFileName.MAX_LENGTH + ReplayFileName.SUFFIX.length(), out.length());
        assertTrue(out.endsWith(ReplayFileName.SUFFIX));
    }
}
