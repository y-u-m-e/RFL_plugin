package com.rfl;

import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * True while the local player is inside a player-owned house (POH) instance.
 *
 * Confirmed against runelite-api 1.12.39 via {@code javap} (the version {@code ./gradlew
 * dependencies --configuration compileClasspath} resolves for {@code latest.release}):
 * {@code Client#getTopLevelWorldView()}, {@code Client#getLocalPlayer()},
 * {@code WorldView#isInstance()}, {@code WorldView#getInstanceTemplateChunks()},
 * {@code WorldView#getPlane()}, {@code Actor#getLocalLocation()}.
 *
 * Chunk data bit layout matches the one place in the client jar's sources that decodes it,
 * {@code net.runelite.client.plugins.devtools.LocationOverlay}: {@code rotation = (c >> 1) &
 * 0x3}, {@code chunkY = (c >> 3) & 0x7FF}, {@code chunkX = (c >> 14) & 0x3FF}, {@code chunkPlane
 * = (c >> 24) & 0x3} (chunk = {@link Constants#CHUNK_SIZE} tiles). A map region is 8x8 chunks,
 * matching {@code WorldPoint#getRegionID()}'s {@code ((x >> 6) << 8) | (y >> 6)} since chunkX ==
 * worldX &gt;&gt; 3, so {@code regionId = ((chunkX >> 3) << 8) | (chunkY >> 3)}.
 */
final class PohDetector
{
    /**
     * POH template region IDs. Nothing in the runelite-api/client jars names these — there is
     * no javap evidence for them — so they are kept as a named constant citing spec §3 /
     * task-4 brief rather than left as inline magic numbers.
     */
    private static final int[] POH_TEMPLATE_REGIONS = {7513, 7514, 7769, 7770};

    boolean inPoh(Client client)
    {
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null || !worldView.isInstance())
        {
            return false;
        }

        Player local = client.getLocalPlayer();
        LocalPoint localPoint = local == null ? null : local.getLocalLocation();
        if (localPoint == null)
        {
            return false;
        }

        int[][][] chunks = worldView.getInstanceTemplateChunks();
        int plane = worldView.getPlane();
        int cx = localPoint.getSceneX() / Constants.CHUNK_SIZE;
        int cy = localPoint.getSceneY() / Constants.CHUNK_SIZE;

        if (plane < 0 || plane >= chunks.length
            || cx < 0 || cx >= chunks[plane].length
            || cy < 0 || cy >= chunks[plane][cx].length)
        {
            return false;
        }

        int chunkData = chunks[plane][cx][cy];
        return chunkData != -1 && isPohRegion(regionIdFromChunkData(chunkData));
    }

    /**
     * Pure region-ID decode, split out from {@link #inPoh} so it's testable without RuneLite
     * types.
     */
    static int regionIdFromChunkData(int chunkData)
    {
        int chunkX = (chunkData >> 14) & 0x3FF;
        int chunkY = (chunkData >> 3) & 0x7FF;
        return ((chunkX >> 3) << 8) | (chunkY >> 3);
    }

    private static boolean isPohRegion(int regionId)
    {
        for (int region : POH_TEMPLATE_REGIONS)
        {
            if (region == regionId)
            {
                return true;
            }
        }
        return false;
    }
}
