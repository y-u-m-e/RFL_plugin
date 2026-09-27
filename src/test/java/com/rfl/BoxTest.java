package com.rfl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BoxTest
{
    private final Box a = new Box(0, 100, 0, 100, 0, 100);

    @Test
    public void separated()
    {
        assertEquals(0, Box.overlapDepth(a, new Box(200, 300, 0, 100, 0, 100)));
    }

    @Test
    public void edgeTouching()
    {
        assertEquals(0, Box.overlapDepth(a, new Box(100, 200, 0, 100, 0, 100)));
    }

    @Test
    public void overlapping()
    {
        assertEquals(30, Box.overlapDepth(a, new Box(70, 170, 0, 100, 0, 100)));
    }

    @Test
    public void contained()
    {
        assertEquals(20, Box.overlapDepth(a, new Box(40, 60, 10, 90, 10, 90)));
    }
}
