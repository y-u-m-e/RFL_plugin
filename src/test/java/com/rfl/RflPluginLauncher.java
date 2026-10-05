package com.rfl;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Runs RuneLite with this plugin loaded, for trying it by hand ({@code ./gradlew runClient}). Not a test.
 */
public class RflPluginLauncher
{
    /**
     * Starts RuneLite and loads this plugin as a built-in external plugin.
     *
     * @param args command line args passed to RuneLite
     * @throws Exception thrown when RuneLite bootstrap fails
     */
    public static void main(final String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(RflPlugin.class);
        RuneLite.main(args);
    }
}
