package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ContactOverlapOverlayTest
{
    @Test
    public void hullDropsInteriorPointsAndKeepsCorners()
    {
        int[][] hull = ContactOverlapOverlay.convexHull(new int[][]{
            {0, 0}, {10, 0}, {10, 10}, {0, 10}, {5, 5}, {3, 7}});
        assertEquals(4, hull.length);
        List<String> corners = Arrays.asList(Arrays.deepToString(hull));
        for (String c : new String[]{"[0, 0]", "[10, 0]", "[10, 10]", "[0, 10]"})
        {
            assertTrue(c, corners.get(0).contains(c));
        }
    }

    @Test
    public void hullOfTooFewPointsIsThosePoints()
    {
        assertEquals(2, ContactOverlapOverlay.convexHull(new int[][]{{0, 0}, {5, 5}}).length);
    }
}
