package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import javax.imageio.ImageIO;

import org.junit.Test;

/**
 * The Plugin Hub packager's checks on files outside the code, run here so a broken listing shows up
 * in the build: the {@code icon.png} at the repository root, the plugin properties, and no
 * service-loader file. Paths are relative to the project directory, where Gradle runs tests.
 */
public class HubPackagingTest
{
    /** The Hub README asks for a 48x72 icon; the packager rejects anything over 50x100 pixels of area. */
    private static final int ICON_WIDTH = 48;
    private static final int ICON_HEIGHT = 72;
    /** The packager's size limit for icon.png. */
    private static final long ICON_MAX_BYTES = 256 * 1024;

    @Test
    public void theListingIconIsAt48By72AtTheRepositoryRoot() throws IOException
    {
        Path icon = Paths.get("icon.png");
        assertTrue("icon.png at the repository root", Files.isRegularFile(icon));
        assertTrue("at most 256 KiB", Files.size(icon) <= ICON_MAX_BYTES);
        BufferedImage image = ImageIO.read(icon.toFile());
        assertNotNull("a PNG ImageIO can read", image);
        assertEquals(ICON_WIDTH, image.getWidth());
        assertEquals(ICON_HEIGHT, image.getHeight());
    }

    @Test
    public void thePropertiesNameThePluginClassAndMatchTheDescriptor() throws IOException
    {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get("runelite-plugin.properties")))
        {
            properties.load(in);
        }
        assertEquals(RflPlugin.class.getName(), properties.getProperty("plugins"));
        assertEquals("RFL Audit", properties.getProperty("displayName"));
        assertTrue(properties.getProperty("description").contains("Nothing is sent anywhere"));
    }

    @Test
    public void thereIsNoServiceLoaderFile()
    {
        // The Hub loads plugins itself and rejects a jar that registers one this way.
        assertFalse(Files.exists(Paths.get("src", "main", "resources", "META-INF", "services",
            "net.runelite.client.plugins.Plugin")));
    }
}
