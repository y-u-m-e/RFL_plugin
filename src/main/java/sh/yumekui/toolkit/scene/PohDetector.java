package sh.yumekui.toolkit.scene;

import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * Detects whether the local player is inside a player-owned house.
 *
 * A POH is an instance built from template chunks. {@link WorldPoint#fromLocalInstance} maps the
 * player's instance position back to the template, and its region is one of the POH template
 * regions. The region list is RuneLite's own, from the Roof Removal plugin's POH override
 * ({@code RoofRemovalConfigOverride.POH}), which matches tiles the same way.
 *
 * <p>The plane passed to {@code fromLocalInstance} is the local player's own resolved plane
 * ({@link ScenePlane}), not whatever the two-argument overload would read off the view
 * internally: right after a reload the view's plane can still be the old scene's for a tick,
 * which would map the lookup to the wrong template chunk.
 */
public final class PohDetector
{
    private static final Set<Integer> POH_TEMPLATE_REGIONS =
        Set.of(7257, 7534, 7535, 7790, 7791, 8046, 8047, 8302, 8303);

    public boolean inPoh(Client client)
    {
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null || !worldView.isInstance())
        {
            return false;
        }

        Player local = client.getLocalPlayer();
        LocalPoint localPoint = local == null ? null : local.getLocalLocation();
        Integer plane = local == null ? null : local.getWorldLocation().getPlane();
        if (localPoint == null || plane == null)
        {
            return false;
        }

        WorldPoint template = WorldPoint.fromLocalInstance(client, localPoint, plane);
        return template != null && isPohRegion(template.getRegionID());
    }

    static boolean isPohRegion(int regionId)
    {
        return POH_TEMPLATE_REGIONS.contains(regionId);
    }
}
