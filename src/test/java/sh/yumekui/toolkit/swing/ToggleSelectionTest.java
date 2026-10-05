package sh.yumekui.toolkit.swing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * {@link ToggleSelection}: a click selects, a second click on the same row clears, a click elsewhere
 * moves the selection, and a row that leaves the list takes the selection with it.
 */
public class ToggleSelectionTest
{
    @Test
    public void clicksSelectToggleAndMove()
    {
        ToggleSelection selection = new ToggleSelection();
        assertEquals("a", selection.click("a"));
        assertTrue(selection.isSelected("a"));
        assertEquals("b", selection.click("b"));
        assertNull("clicking the selected row clears it", selection.click("b"));
        assertNull(selection.selected());
    }

    @Test
    public void aRowThatLeavesTheListIsUnselected()
    {
        ToggleSelection selection = new ToggleSelection();
        selection.click("a");
        assertFalse("still listed", selection.retain(List.of("a", "b")));
        assertTrue("gone", selection.retain(List.of("b")));
        assertNull(selection.selected());
    }
}
