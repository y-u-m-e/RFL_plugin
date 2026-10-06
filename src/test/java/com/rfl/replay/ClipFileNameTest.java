package com.rfl.replay;

import com.rfl.contact.Collision;

import static org.junit.Assert.assertEquals;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.Test;

/** {@link ClipFileName}: every token, the fallbacks, names with spaces and the carrier going first. */
public class ClipFileNameTest
{
    private static final ZoneId ZONE = ZoneId.of("Europe/London");
    private static final long SAVED = LocalDateTime.of(2026, 10, 5, 21, 4, 9).atZone(ZONE).toInstant().toEpochMilli();
    private static final long HIT = LocalDateTime.of(2026, 10, 5, 21, 3, 1).atZone(ZONE).toInstant().toEpochMilli();

    private static Collision collision(String a, String b, String... ball)
    {
        return new Collision(a, b, List.of(ball), HIT, HIT + 600, 10, 11, 330, 0, 0, 0, 50, 50, 12);
    }

    private static ClipFileName.Facts facts(Collision collision)
    {
        return new ClipFileName.Facts(SAVED, 330, "Ref Bob", "2026-10-05_200102_w330.rflr.gz", 180, collision);
    }

    private static String name(String template, Collision collision)
    {
        return ClipFileName.fileName(template, facts(collision), ZONE);
    }

    @Test
    public void theDefaultTemplateNamesTheLatestCollision()
    {
        assertEquals("{date}_{time}_w{world}_{collision}", ClipFileName.DEFAULT_TEMPLATE);
        assertEquals("2026-10-05_210409_w330_Amy-vs-Zed.rflr.gz",
            name(ClipFileName.DEFAULT_TEMPLATE, collision("Amy", "Zed", "Amy")));
    }

    @Test
    public void everyTokenExpands()
    {
        assertEquals("2026-10-05.210409.330.Ref Bob.2026-10-05_200102_w330.3-00.Amy-vs-Zed.210301.rflr.gz",
            name("{date}.{time}.{world}.{player}.{source}.{length}.{collision}.{collisionTime}",
                collision("Amy", "Zed", "Amy")));
    }

    @Test
    public void noCollisionExpandsToNoCollisionAndDropsItsTimeWithOneSeparator()
    {
        assertEquals("2026-10-05_210409_w330_no-collision.rflr.gz", name(ClipFileName.DEFAULT_TEMPLATE, null));
        assertEquals("review_no-collision_3-00.rflr.gz", name("review_{collisionTime}_{collision}_{length}", null));
        assertEquals("review_3-00.rflr.gz", name("review_{length}_{collisionTime}", null));
        assertEquals("3-00.rflr.gz", name("{collisionTime}-{length}", null));
        assertEquals("with a time it stays", "review_210301_3-00.rflr.gz",
            name("review_{collisionTime}_{length}", collision("Amy", "Zed")));
    }

    @Test
    public void namesWithSpacesBecomeOneWord()
    {
        assertEquals("Iron_Amy-vs-Big_Zed.rflr.gz", name("{collision}", collision("Iron Amy", "Big  Zed")));
    }

    @Test
    public void theBallCarrierGoesFirstOnlyWhenExactlyOneHeldIt()
    {
        assertEquals("Zed-vs-Amy.rflr.gz", name("{collision}", collision("Amy", "Zed", "Zed")));
        assertEquals("Amy-vs-Zed.rflr.gz", name("{collision}", collision("Amy", "Zed", "Amy")));
        assertEquals("both held one: as recorded", "Amy-vs-Zed.rflr.gz",
            name("{collision}", collision("Amy", "Zed", "Amy", "Zed")));
        assertEquals("nobody held it: as recorded", "Amy-vs-Zed.rflr.gz", name("{collision}", collision("Amy", "Zed")));
    }

    @Test
    public void lengthIsMinutesDashSeconds()
    {
        assertEquals("3-00", ClipFileName.length(180));
        assertEquals("1-30", ClipFileName.length(90));
        assertEquals("0-30", ClipFileName.length(30));
        assertEquals("5-00", ClipFileName.length(300));
    }

    @Test
    public void sourceDropsTheSuffix()
    {
        assertEquals("game-2", ClipFileName.source("game-2.rflr.gz"));
        assertEquals("unknown", ClipFileName.source(null));
    }

    @Test
    public void emptyOrInvalidFallsBackToTheDefault()
    {
        String fallback = "2026-10-05_210409_w330_no-collision.rflr.gz";
        assertEquals(fallback, name("", null));
        assertEquals(fallback, name("{nope}", null));
        assertEquals(fallback, name("bad}", null));
        assertEquals("nothing left once the empty time goes", fallback, name("{collisionTime}", null));
    }

    @Test
    public void unsafeCharactersAreReplaced()
    {
        assertEquals("a_b_Amy-vs-Zed.rflr.gz", name("a:b/{collision}", collision("Amy", "Zed")));
    }
}
