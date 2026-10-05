package com.rfl.contact;

import static com.rfl.Fixtures.THROUGH;
import static com.rfl.Fixtures.WALL;
import static com.rfl.Fixtures.mesh;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.rfl.Handegg;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import sh.yumekui.toolkit.geom.TriangleMesh;

/** A gnomeball in the weapon slot makes its holder the ball holder, exactly like a handegg. */
public class BallHolderTest
{
    private static final int EMPTY = -1;

    /** The holders {@link ContactDetector} hands the tracker: every weapon slot that is a held ball. */
    private static Set<String> holders(Map<String, Integer> weapons)
    {
        Set<String> holders = new HashSet<>();
        weapons.forEach((name, item) ->
        {
            if (Handegg.isHeldBall(item))
            {
                holders.add(name);
            }
        });
        return holders;
    }

    private static Map<String, TriangleMesh> touching()
    {
        return Map.of("Amy", mesh(WALL), "Zed", mesh(THROUGH));
    }

    @Test
    public void theGnomeballIsItsGamevalItemAndAHeldBallButNotAHandegg()
    {
        assertEquals(751, Handegg.GNOMEBALL);
        assertEquals(ItemID.BALL_GNOMEBALL_GAME, Handegg.GNOMEBALL);
        assertTrue(Handegg.isHeldBall(Handegg.GNOMEBALL));
        assertFalse(Handegg.isHandegg(Handegg.GNOMEBALL));
        for (int handegg : Handegg.ITEMS)
        {
            assertTrue(Handegg.isHeldBall(handegg));
        }
        assertFalse(Handegg.isHeldBall(ItemID.ABYSSAL_WHIP));
        assertFalse(Handegg.isHeldBall(EMPTY));
    }

    @Test
    public void aGnomeballHolderGatesCollisions()
    {
        ContactTracker t = new ContactTracker((x, y) -> TileRef.NONE, () -> 330);
        Set<String> nobody = holders(Map.of("Amy", ItemID.ABYSSAL_WHIP, "Zed", EMPTY));
        assertTrue("no ball, no collision", t.update(touching(), nobody, 1000, 5, false).isEmpty());

        Set<String> gnomeball = holders(Map.of("Amy", Handegg.GNOMEBALL, "Zed", EMPTY));
        assertEquals(Set.of("Amy"), gnomeball);
        List<TriangleMesh.Hits> started = t.update(touching(), gnomeball, 1020, 5, false);
        assertEquals(1, started.size());
    }

    @Test
    public void theGnomeballHolderIsNamedInTheEvent()
    {
        ContactTracker t = new ContactTracker((x, y) -> TileRef.NONE, () -> 330);
        t.update(touching(), holders(Map.of("Amy", Handegg.GNOMEBALL, "Zed", EMPTY)), 1000, 5, false);
        t.flush(1500, 6);
        List<Collision> finished = t.takeFinished();
        assertEquals(1, finished.size());
        // The ball list is what the day file, the replay ev line and the panel's marked holder show.
        assertEquals(List.of("Amy"), finished.get(0).ball);
    }

    @Test
    public void theGnomeballsHeldGeometryIsLeftOutToo()
    {
        assertEquals(528, Handegg.wornModel(Handegg.GNOMEBALL));
        assertEquals(28545, Handegg.wornModel(ItemID.EASTER18_HANDEGG_CHAOS));
        assertEquals(-1, Handegg.wornModel(ItemID.ABYSSAL_WHIP));
    }
}
