package com.playercollision;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Local launcher used to run RuneLite with this plugin loaded for manual testing.
 */
public class PlayerCollisionPluginTest
{
    /**
     * Starts RuneLite and loads this plugin as a built-in external plugin.
     *
     * @param args command line args passed to RuneLite
     * @throws Exception thrown when RuneLite bootstrap fails
     */
    public static void main(final String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(PlayerCollisionPlugin.class);
        RuneLite.main(args);
    }
}
