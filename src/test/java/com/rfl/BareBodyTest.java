package com.rfl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;

public class BareBodyTest
{
    private static BareBody.KitFile bundled()
    {
        return new Gson().fromJson(new InputStreamReader(
            BareBody.class.getResourceAsStream("identkits.json"), StandardCharsets.UTF_8), BareBody.KitFile.class);
    }

    @Test
    public void bundledTableHasEveryBodyPartOfBothGenders()
    {
        BareBody.KitFile file = bundled();
        assertNotNull(file.source);
        assertTrue(file.kits.size() > 200);
        BareBody.Kit bald = file.kits.get(0);
        assertEquals(0, bald.bodyPart);
        assertArrayEquals(new int[]{230}, bald.models);
        Map<Integer, Integer> defaults = BareBody.defaults(file.kits);
        for (int part = 0; part < 14; part++)
        {
            assertTrue("part " + part, defaults.containsKey(part));
        }
    }

    @Test
    public void hiddenOrForeignKitsFallBackToTheGenderDefault()
    {
        Map<Integer, BareBody.Kit> kits = bundled().kits;
        Map<Integer, Integer> defaults = BareBody.defaults(kits);
        int maleTorso = defaults.get(2);
        int femaleHair = defaults.get(BareBody.FEMALE_BODY_PART_OFFSET);

        // Male: own hair kit 1 kept, torso hidden under a platebody (-1) -> default torso,
        // a female hair kit in the hair slot -> default male hair.
        int[] male = BareBody.resolve(kits, defaults, false, new int[]{1, -1, -1, -1, -1, -1, -1});
        assertEquals(1, male[0]);
        assertEquals(maleTorso, male[2]);
        int[] wrongGender = BareBody.resolve(kits, defaults, false, new int[]{femaleHair, -1, -1, -1, -1, -1, -1});
        assertEquals((int) defaults.get(0), wrongGender[0]);

        int[] female = BareBody.resolve(kits, defaults, true, new int[]{-1, -1, -1, -1, -1, -1, -1});
        for (int i = 0; i < female.length; i++)
        {
            assertEquals(i + BareBody.FEMALE_BODY_PART_OFFSET, kits.get(female[i]).bodyPart);
        }
    }
}
