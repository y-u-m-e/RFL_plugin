package com.rfl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/** {@link RflConfig}: every switch starts off; the user opts in to each one. */
public class RflConfigTest
{
    private final RflConfig config = new RflConfig()
    {
    };

    @Test
    public void everySwitchDefaultsToOff()
    {
        assertFalse(config.showPanel());
        assertFalse(config.reportContacts());
        assertFalse(config.saveCollisions());
        assertFalse(config.highlightContacts());
        assertFalse(config.showTouchingTriangles());
        assertFalse(config.showHitboxes());
        assertFalse(config.detectIncompletes());
        assertFalse(config.incompleteChatMessage());
        assertFalse(config.highlightIncompletes());
        assertFalse(config.recordReplays());
        assertFalse(config.recordOverheadChat());
    }

    /** Catches a switch added later with an "on" default, without reflection: reads the source. */
    @Test
    public void noBooleanSettingInTheSourceDefaultsToOn() throws IOException
    {
        Path source = Paths.get("src", "main", "java", "com", "rfl", "RflConfig.java");
        assertTrue("run from the project directory", Files.isRegularFile(source));
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("default boolean (\\w+)\\(\\)\\s*\\{\\s*return (\\w+);").matcher(text);
        List<String> on = new ArrayList<>();
        int switches = 0;
        while (m.find())
        {
            switches++;
            if (!"false".equals(m.group(2)))
            {
                on.add(m.group(1));
            }
        }
        // Any count passes, so a new switch needs no edit here; zero would mean the pattern broke.
        assertTrue("no switches found: the pattern no longer matches RflConfig.java", switches > 0);
        assertTrue("on by default: " + on, on.isEmpty());
    }
}
