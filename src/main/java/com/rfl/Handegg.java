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

    /**
     * The model all three handeggs are drawn with in the hand (item op 45/48, male and female alike;
     * the three differ only by recolour). Read from the game cache on 2026-10-04: 114 vertices,
     * 224 faces, which is exactly the block a held handegg adds to a player's model in every
     * recorded replay. RuneLite has no gameval for model ids, hence the number.
     */
    static final int WORN_MODEL = 28545;

    private Handegg()
    {
    }

    /**
     * The model drawn in the hand for a weapon-slot item that is a held ball, or -1. Used to find
     * and leave out the ball's own faces from the holder's hitbox.
     */
    public static int wornModel(int itemId)
    {
        return isHandegg(itemId) ? WORN_MODEL : -1;
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
