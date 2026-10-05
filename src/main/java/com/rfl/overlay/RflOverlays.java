package com.rfl.overlay;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;

/** The plugin's four overlays, added on start-up and removed on shut-down together. */
@Singleton
public final class RflOverlays
{
    private final OverlayManager overlayManager;
    private final List<Overlay> overlays;

    @Inject
    RflOverlays(OverlayManager overlayManager, ContactHighlightOverlay contactHighlights, HitboxOverlay hitboxes,
        TeamTileOverlay teamTiles, EventTileOverlay eventTiles)
    {
        this.overlayManager = overlayManager;
        this.overlays = List.of(contactHighlights, hitboxes, teamTiles, eventTiles);
    }

    public void addAll()
    {
        overlays.forEach(overlayManager::add);
    }

    public void removeAll()
    {
        overlays.forEach(overlayManager::remove);
    }
}
