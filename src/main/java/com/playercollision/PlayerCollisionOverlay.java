package com.playercollision;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Placeholder overlay kept for compatibility while the plugin uses panel-based workflows.
 */
@Singleton
public class PlayerCollisionOverlay extends Overlay
{
    /**
     * Creates an overlay placeholder and keeps dependency bindings stable.
     *
     * @param plugin plugin reference
     * @param config plugin config reference
     */
    @Inject
    public PlayerCollisionOverlay(final PlayerCollisionPlugin plugin, final PlayerCollisionConfig config)
    {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    /**
     * Renders nothing because this plugin now focuses on clipboard and panel tools.
     *
     * @param graphics drawing context provided by RuneLite
     * @return null because no overlay is rendered
     */
    @Override
    public Dimension render(final Graphics2D graphics)
    {
        return null;
    }
}
