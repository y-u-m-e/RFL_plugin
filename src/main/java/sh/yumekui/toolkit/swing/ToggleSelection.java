package sh.yumekui.toolkit.swing;

import java.util.List;

/**
 * Click-to-toggle selection of one list row: a click selects a row, a second click on it clears it,
 * a click on another row moves the selection, and a row that disappears from the list clears it.
 * Rows are compared by equality, so value rows from a rebuilt list still match. No Swing calls;
 * unit-tested on its own.
 *
 * <p>Threads: EDT only.
 */
public final class ToggleSelection
{
    private Object selected;

    /** @return the row now selected, or null when the click cleared it */
    public Object click(Object row)
    {
        selected = row == null || row.equals(selected) ? null : row;
        return selected;
    }

    public void clear()
    {
        selected = null;
    }

    public Object selected()
    {
        return selected;
    }

    public boolean isSelected(Object row)
    {
        return selected != null && selected.equals(row);
    }

    /**
     * Keeps the selection only if the row is still in {@code rows}.
     *
     * @return whether the selection was dropped
     */
    public boolean retain(List<?> rows)
    {
        if (selected != null && !rows.contains(selected))
        {
            selected = null;
            return true;
        }
        return false;
    }
}
