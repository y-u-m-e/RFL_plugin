package com.rfl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PohDetectorTest
{
    @Test
    public void recognisesRuneLitePohTemplateRegions()
    {
        for (int region : new int[]{7257, 7534, 7535, 7790, 7791, 8046, 8047, 8302, 8303})
        {
            assertTrue(String.valueOf(region), PohDetector.isPohRegion(region));
        }
    }

    @Test
    public void rejectsTheOldGuessedRegionsAndOrdinaryRegions()
    {
        assertFalse(PohDetector.isPohRegion(7513));
        assertFalse(PohDetector.isPohRegion(12850)); // Lumbridge
    }
}
