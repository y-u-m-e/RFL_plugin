package com.rfl.contact;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.GsonBuilder;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;

/**
 * {@link BareBody}'s bundled identity-kit table and its kit choice: every body part has a default
 * for both genders, and a slot hidden under equipment or holding the other gender's kit falls back
 * to that default. The posing itself needs a client; {@code VertexSnapshotTest} covers its cache guard.
 */
public class BareBodyTest
{
    private static final int MIN_KITS = 200;
    /** The bald head's model id in the cache. */
    private static final int BALD_MODEL = 230;
    /** Seven body parts (hair, jaw, torso, arms, hands, legs, feet) for each of two genders. */
    private static final int BODY_PARTS_BOTH_GENDERS = 2 * BareBody.FEMALE_BODY_PART_OFFSET;
    /** Body part 2 of the seven. */
    private static final int TORSO = 2;
    /** All seven slots hidden under equipment. */
    private static final int[] ALL_HIDDEN = {-1, -1, -1, -1, -1, -1, -1};
    private static BareBody.KitFile bundled()
    {
        return new GsonBuilder().create().fromJson(new InputStreamReader(
            BareBody.class.getResourceAsStream("identkits.json"), StandardCharsets.UTF_8), BareBody.KitFile.class);
    }

    @Test
    public void bundledTableHasEveryBodyPartOfBothGenders()
    {
        BareBody.KitFile file = bundled();
        assertNotNull("the table says which cache it came from", file.source);
        // The game has a few hundred identity kits; far fewer means a truncated extract.
        assertTrue(file.kits.size() > MIN_KITS);
        // Kit 0 is the male bald head (body part 0, hair), one model.
        BareBody.Kit bald = file.kits.get(0);
        assertEquals(0, bald.bodyPart);
        assertArrayEquals(new int[]{BALD_MODEL}, bald.models);
        Map<Integer, Integer> defaults = BareBody.defaults(file.kits);
        for (int part = 0; part < BODY_PARTS_BOTH_GENDERS; part++)
        {
            assertTrue("part " + part, defaults.containsKey(part));
        }
    }

    @Test
    public void hiddenOrForeignKitsFallBackToTheGenderDefault()
    {
        Map<Integer, BareBody.Kit> kits = bundled().kits;
        Map<Integer, Integer> defaults = BareBody.defaults(kits);
        int maleTorso = defaults.get(TORSO);
        int femaleHair = defaults.get(BareBody.FEMALE_BODY_PART_OFFSET);

        // Male: own hair kit 1 kept, torso hidden under a platebody (-1) -> default torso,
        // a female hair kit in the hair slot -> default male hair.
        int[] male = BareBody.resolve(kits, defaults, false, new int[]{1, -1, -1, -1, -1, -1, -1});
        assertEquals(1, male[0]);
        assertEquals(maleTorso, male[2]);
        int[] wrongGender = BareBody.resolve(kits, defaults, false, new int[]{femaleHair, -1, -1, -1, -1, -1, -1});
        assertEquals((int) defaults.get(0), wrongGender[0]);

        int[] female = BareBody.resolve(kits, defaults, true, ALL_HIDDEN);
        for (int i = 0; i < female.length; i++)
        {
            assertEquals(i + BareBody.FEMALE_BODY_PART_OFFSET, kits.get(female[i]).bodyPart);
        }
    }
}
