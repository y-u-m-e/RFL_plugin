package com.rfl.contact;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rfl.Handegg;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.BeforeClass;
import org.junit.Test;
import sh.yumekui.toolkit.geom.TriangleMesh;

/**
 * {@link HeldBallFaces} on a real case: Sharnos catching a handegg next to C J N J, rebuilt from
 * the posed models in a recording (ramsvdrags_half2, 2026-10-09), trimmed to the two players at
 * two cycles. The recorded model already holds the egg: a 224-face block over 114 vertices at
 * faces 432..655, the same counts as the cache's handegg model.
 *
 * <ul>
 *   <li>692073, the catch: Sharnos holds the egg above his head into C J N J's hat. Only the egg
 *   crosses C J N J, so with its faces left out nothing touches.</li>
 *   <li>692041: their arms cross (17 touching triangle pairs, the count the plugin saved for the
 *   collision), which is body contact and still counts.</li>
 * </ul>
 */
public class HeldBallRealCaseTest
{
    private static final String SHARNOS = "Sharnos";
    private static final String CJNJ = "C J N J";
    private static final int[] EGG_BLOCK = {432, 656};
    /** Equipment ids above this are items (id + this), as the composition stores them. */
    private static final int ITEM_OFFSET = 2048;

    private static JsonObject players;
    private static HeldBallFaces.Shape egg;

    @BeforeClass
    public static void load() throws Exception
    {
        try (InputStream in = HeldBallRealCaseTest.class.getResourceAsStream("held-ball-catch-692073.json"))
        {
            assertNotNull(in);
            JsonObject fixture = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getAsJsonObject();
            players = fixture.getAsJsonObject("players");
            int[] shape = ints(fixture.getAsJsonArray("eggShape"));
            int n = shape.length / 3;
            int[] a = new int[n];
            int[] b = new int[n];
            int[] c = new int[n];
            split(shape, a, b, c);
            egg = new HeldBallFaces.Shape(a, b, c, n);
        }
    }

    @Test
    public void matchesTheEggBlockInThisOutfit()
    {
        int[] equipment = ints(players.getAsJsonObject(SHARNOS).getAsJsonArray("equipment"));
        int weapon = equipment[3] - ITEM_OFFSET;
        assertTrue("Sharnos's weapon slot holds a handegg", Handegg.isHandegg(weapon));
        assertArrayEquals(EGG_BLOCK, range(SHARNOS));
        // C J N J holds nothing, so nothing of theirs is left out.
        int[] other = ints(players.getAsJsonObject(CJNJ).getAsJsonArray("equipment"));
        assertEquals(-1, Handegg.wornModel(other[3] - ITEM_OFFSET));
    }

    @Test
    public void eggAgainstTheHatAtTheCatchIsNoContact()
    {
        TriangleMesh full = mesh(SHARNOS, "692073", HeldBallFaces.NONE);
        TriangleMesh hat = mesh(CJNJ, "692073", HeldBallFaces.NONE);
        TriangleMesh.Hits unmasked = new TriangleMesh.Intersector().intersect(full, hat, Integer.MAX_VALUE);
        assertNotNull("unmasked, the egg crosses C J N J", unmasked);
        for (int k = 0; k < unmasked.count; k++)
        {
            int face = unmasked.pairs[k * 2];
            assertTrue("only egg faces touch, not face " + face, face >= EGG_BLOCK[0] && face < EGG_BLOCK[1]);
        }

        TriangleMesh carrier = mesh(SHARNOS, "692073", range(SHARNOS));
        assertNull(new TriangleMesh.Intersector().intersect(carrier, hat, Integer.MAX_VALUE));
        ContactTracker tracker = tracker();
        tracker.setCycle(692073);
        assertTrue(tracker.update(Map.of(SHARNOS, carrier, CJNJ, hat), Set.of(SHARNOS), 1000, 6309, true).isEmpty());
        assertTrue(tracker.contactsAt(692073, 3).isEmpty());
    }

    @Test
    public void armsCrossingIsStillContact()
    {
        TriangleMesh carrier = mesh(SHARNOS, "692041", range(SHARNOS));
        TriangleMesh other = mesh(CJNJ, "692041", HeldBallFaces.NONE);
        ContactTracker tracker = tracker();
        tracker.setCycle(692041);
        List<TriangleMesh.Hits> started = tracker.update(Map.of(SHARNOS, carrier, CJNJ, other), Set.of(SHARNOS),
            1000, 6308, true);
        assertEquals(1, started.size());
        assertEquals(17, started.get(0).count);
        assertEquals(List.of(CJNJ), tracker.contactsAt(692041, 3).get(SHARNOS));
    }

    /**
     * Why the recording still logged C J N J at the catch: the collision opened by the arms at
     * 692041 is still open at 692073, because a collision holds while the two bounds overlap, so
     * the incomplete rule counts it even though no triangles touch at the catch.
     */
    @Test
    public void anOpenCollisionStillCountsAtTheCatch()
    {
        ContactTracker tracker = tracker();
        tracker.setCycle(692041);
        tracker.update(Map.of(SHARNOS, mesh(SHARNOS, "692041", range(SHARNOS)),
            CJNJ, mesh(CJNJ, "692041", HeldBallFaces.NONE)), Set.of(SHARNOS), 1000, 6308, true);
        tracker.setCycle(692073);
        TriangleMesh carrier = mesh(SHARNOS, "692073", range(SHARNOS));
        TriangleMesh hat = mesh(CJNJ, "692073", HeldBallFaces.NONE);
        assertTrue(TriangleMesh.boundsOverlap(carrier, hat));
        tracker.update(Map.of(SHARNOS, carrier, CJNJ, hat), Set.of(SHARNOS), 1640, 6309, true);
        assertEquals(List.of(CJNJ), tracker.contactsAt(692073, 3).get(SHARNOS));
    }

    private static int[] range(String name)
    {
        JsonObject player = players.getAsJsonObject(name);
        int[] equipment = ints(player.getAsJsonArray("equipment"));
        int worn = Handegg.wornModel(equipment[3] - ITEM_OFFSET);
        int[] faces = ints(player.getAsJsonArray("faces"));
        int n = faces.length / 3;
        int[] a = new int[n];
        int[] b = new int[n];
        int[] c = new int[n];
        split(faces, a, b, c);
        // The cache's handegg model, as this recording shows it: the block's own face indices.
        return new HeldBallFaces(id -> id == worn ? egg : null)
            .range(name, player.get("gender").getAsInt(), equipment, worn, a, b, c, n);
    }

    /** The mesh {@link ContactDetector} builds from a posed model, leaving out {@code skip}. */
    private static TriangleMesh mesh(String name, String cycle, int[] skip)
    {
        JsonObject player = players.getAsJsonObject(name);
        JsonObject pose = player.getAsJsonObject("poses").getAsJsonObject(cycle);
        int[] v = ints(pose.getAsJsonArray("v"));
        int[] faces = ints(player.getAsJsonArray("faces"));
        int count = v.length / 3;
        float[] xs = new float[count];
        float[] ys = new float[count];
        float[] zs = new float[count];
        for (int i = 0; i < count; i++)
        {
            xs[i] = v[i * 3];
            ys[i] = v[i * 3 + 1];
            zs[i] = v[i * 3 + 2];
        }
        int n = faces.length / 3;
        int[] a = new int[n];
        int[] b = new int[n];
        int[] c = new int[n];
        split(faces, a, b, c);
        return TriangleMesh.from(xs, ys, zs, count, a, b, c, n, null, null, pose.get("orient").getAsInt(),
            pose.get("x").getAsInt(), pose.get("y").getAsInt(), skip[0], skip[1]);
    }

    private static ContactTracker tracker()
    {
        return new ContactTracker((x, y) -> TileRef.NONE, () -> 354);
    }

    private static void split(int[] flat, int[] a, int[] b, int[] c)
    {
        for (int i = 0; i < a.length; i++)
        {
            a[i] = flat[i * 3];
            b[i] = flat[i * 3 + 1];
            c[i] = flat[i * 3 + 2];
        }
    }

    private static int[] ints(JsonArray array)
    {
        int[] out = new int[array.size()];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = array.get(i).getAsInt();
        }
        return out;
    }
}
