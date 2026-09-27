package com.rfl;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * RFL match audit plugin. Reporting behavior is added in later tasks.
 */
@PluginDescriptor(
    name = "RFL Audit"
)
public class RflPlugin extends Plugin
{
    @Inject
    private RflConfig config;

    /**
     * Provides the plugin configuration through RuneLite's config manager.
     *
     * @param configManager central RuneLite config manager
     * @return plugin config instance
     */
    @Provides
    RflConfig provideConfig(final ConfigManager configManager)
    {
        return configManager.getConfig(RflConfig.class);
    }
}
