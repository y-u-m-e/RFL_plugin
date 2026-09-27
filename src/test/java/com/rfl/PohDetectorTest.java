package com.rfl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PohDetectorTest
{
    @Test
    public void decodesRegionFromChunkCoordinates()
    {
        int chunkX = 100;
        int chunkY = 200;
        int chunkData = (chunkX << 14) | (chunkY << 3);

        assertEquals(((chunkX >> 3) << 8) | (chunkY >> 3), PohDetector.regionIdFromChunkData(chunkData));
    }

    @Test
    public void decodesKnownPohRegion()
    {
        // Inverse of WorldPoint#getRegionID's ((x >> 6) << 8) | (y >> 6): region 7513 is
        // regionX=29, regionY=25, i.e. chunkX=29*8, chunkY=25*8.
        int regionX = 7513 >> 8;
        int regionY = 7513 & 0xFF;
        int chunkData = ((regionX * 8) << 14) | ((regionY * 8) << 3);

        assertEquals(7513, PohDetector.regionIdFromChunkData(chunkData));
    }
}
