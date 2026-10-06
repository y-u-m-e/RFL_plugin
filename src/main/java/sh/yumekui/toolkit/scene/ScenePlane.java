package sh.yumekui.toolkit.scene;

import net.runelite.api.Client;
import net.runelite.api.Player;

/**
 * The plane the local player is really standing on, read from their resolved world location
 * rather than {@link net.runelite.api.WorldView#getPlane()}.
 *
 * <p>Right after a LOADING reload the client's view-plane field has not settled yet: for the
 * first logged-in tick or two it can still report the previous scene's plane (or 0) while the
 * new scene's tiles, heights and locs are already for the new plane. A player's own world
 * location, by contrast, is only ever reported for the plane they are really on, instance or
 * not, so it stays correct across the reload. Anything that reads the plane on the tick a reload
 * may have just happened — a scene scan, an instance detector, a tile logged for a collision or
 * incomplete — should read it from here instead of the view.
 *
 * <p>Returns null while there is no local player to read it from (also most likely early in a
 * reload); callers should hold off rather than fall back to the unsettled view plane.
 */
public final class ScenePlane
{
    private ScenePlane()
    {
    }

    /** The local player's resolved plane, or null while there is no local player yet. */
    public static Integer settled(Client client)
    {
        Player local = client.getLocalPlayer();
        return local == null ? null : local.getWorldLocation().getPlane();
    }
}
