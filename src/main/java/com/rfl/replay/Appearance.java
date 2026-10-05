package com.rfl.replay;

import java.util.Arrays;
import java.util.Objects;

/** One player's appearance this game tick: gender, equipment and colours, for {@code app} lines. */
final class Appearance
{
    final String name;
    final int gender;
    /** The composition's equipment ids; a copy, since the client changes its own in place. */
    final int[] equipment;
    /** The composition's body colours; a copy, as above. */
    final int[] colors;

    Appearance(String name, int gender, int[] equipment, int[] colors)
    {
        this.name = name;
        this.gender = gender;
        this.equipment = equipment;
        this.colors = colors;
    }

    int hash()
    {
        return hash(gender, equipment, colors);
    }

    /** The appearance hash, also computed per frame by the recorder (no allocation beyond boxing). */
    static int hash(int gender, int[] equipment, int[] colors)
    {
        return Objects.hash(gender, Arrays.hashCode(equipment), Arrays.hashCode(colors));
    }
}
