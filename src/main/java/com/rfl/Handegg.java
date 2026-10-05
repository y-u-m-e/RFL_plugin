package com.rfl;

import java.util.Set;

import net.runelite.api.Projectile;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpotanimID;

/**
 * The Easter 2018 handegg the league plays with: its three weapon items and the projectile drawn
 * while one is thrown, by RuneLite gameval id.
 */
public final class Handegg
{
    /** Holy, Peaceful and Chaotic handegg, as worn in the weapon slot. */
    public static final Set<Integer> ITEMS = Set.of(
        ItemID.EASTER18_HANDEGG_LIGHT, ItemID.EASTER18_HANDEGG_BALANCE, ItemID.EASTER18_HANDEGG_CHAOS);

    /** The thrown handegg's in-flight projectile, one per handegg type. */
    static final Set<Integer> PROJECTILES = Set.of(
        SpotanimID.EASTER18_HANDEGG_TRAVEL_SARA, SpotanimID.EASTER18_HANDEGG_TRAVEL_GUTH,
        SpotanimID.EASTER18_HANDEGG_TRAVEL_ZAM);

    private Handegg()
    {
    }

    /** Whether a weapon-slot item id is a handegg. */
    public static boolean isHandegg(int itemId)
    {
        return ITEMS.contains(itemId);
    }

    /** Whether a projectile is a thrown handegg. */
    public static boolean isThrown(Projectile projectile)
    {
        return PROJECTILES.contains(projectile.getId());
    }

    /** Whether any of the drawn projectiles is a thrown handegg. */
    public static boolean anyInFlight(Iterable<Projectile> projectiles)
    {
        for (Projectile projectile : projectiles)
        {
            if (isThrown(projectile))
            {
                return true;
            }
        }
        return false;
    }
}
