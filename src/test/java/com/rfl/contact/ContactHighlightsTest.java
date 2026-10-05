package com.rfl.contact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** {@link ContactHighlights}: tile highlights fade linearly and are dropped once their time is up. */
public class ContactHighlightsTest
{
    @Test
    public void fadesLinearlyOverTheDuration()
    {
        ContactHighlights highlights = new ContactHighlights();
        highlights.add(128, 256, 1000);

        List<ContactHighlights.Highlight> atStart = highlights.active(1000, 1200);
        assertEquals(1, atStart.size());
        assertEquals(128, atStart.get(0).x);
        assertEquals(256, atStart.get(0).y);
        assertEquals(1.0f, atStart.get(0).alpha, 0.001f);

        assertEquals(0.5f, highlights.active(1600, 1200).get(0).alpha, 0.001f);
    }

    @Test
    public void expiresAfterTheDuration()
    {
        ContactHighlights highlights = new ContactHighlights();
        highlights.add(0, 0, 1000);
        assertTrue(highlights.active(2200, 1200).isEmpty());
        assertTrue(highlights.active(1000, 1200).isEmpty());
    }

    @Test
    public void clearRemovesEverything()
    {
        ContactHighlights highlights = new ContactHighlights();
        highlights.add(0, 0, 1000);
        highlights.clear();
        assertTrue(highlights.active(1000, 1200).isEmpty());
    }
}
