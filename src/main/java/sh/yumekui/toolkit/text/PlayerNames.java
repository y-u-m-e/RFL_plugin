package sh.yumekui.toolkit.text;

import java.util.Locale;
import net.runelite.api.Player;
import net.runelite.client.util.Text;

/**
 * Player names the way RuneLite compares them: {@link #sanitized} for display and as a map key
 * within one client, and {@link #matchKey} for matching a typed or saved name against a player in
 * game, so "Ref_Bob" and "ref bob" are one player.
 */
public final class PlayerNames
{
    private PlayerNames()
    {
    }

    /** The player's name with the client's tags and odd spaces removed ({@link Text#sanitize}), or null. */
    public static String sanitized(Player player)
    {
        String name = player == null ? null : player.getName();
        return name == null ? null : Text.sanitize(name);
    }

    /**
     * The matching key for a name: RuneLite's Jagex form ({@link Text#toJagexName}), lower case. Null
     * for a null or blank name.
     */
    public static String matchKey(String name)
    {
        if (name == null)
        {
            return null;
        }
        String jagex = Text.toJagexName(name);
        return jagex.isEmpty() ? null : jagex.toLowerCase(Locale.ROOT);
    }
}
