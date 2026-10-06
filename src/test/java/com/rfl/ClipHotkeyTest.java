package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JPanel;

import net.runelite.client.config.Keybind;

import org.junit.Test;

/**
 * The Save replay clip hotkey: it fires once per press of the configured key, never while unset,
 * and the plugin registers it with the client's KeyManager for as long as it runs.
 */
public class ClipHotkeyTest
{
    /**
     * A key event as the client delivers it. A hand-made KeyEvent has no extended key code (only
     * the toolkit sets one) and Keybind matches on it, so this one reports its key code there.
     */
    private static KeyEvent key(int type, int code, int modifiers)
    {
        return new KeyEvent(new JPanel(), type, 0L, modifiers, code, KeyEvent.CHAR_UNDEFINED)
        {
            @Override
            public int getExtendedKeyCode()
            {
                return code;
            }
        };
    }

    @Test
    public void theConfiguredKeySavesOncePerPress()
    {
        AtomicInteger saves = new AtomicInteger();
        ClipHotkey hotkey = new ClipHotkey(() -> new Keybind(KeyEvent.VK_F7, InputEvent.CTRL_DOWN_MASK),
            saves::incrementAndGet);
        hotkey.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F7, InputEvent.CTRL_DOWN_MASK));
        hotkey.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F7, InputEvent.CTRL_DOWN_MASK));
        assertEquals("held down: one save", 1, saves.get());
        hotkey.keyReleased(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_F7, InputEvent.CTRL_DOWN_MASK));
        hotkey.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F7, InputEvent.CTRL_DOWN_MASK));
        assertEquals(2, saves.get());
        hotkey.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F8, InputEvent.CTRL_DOWN_MASK));
        assertEquals("another key does nothing", 2, saves.get());
    }

    @Test
    public void unsetByDefaultSoNoKeySaves()
    {
        RflConfig config = new RflConfig()
        {
        };
        assertEquals(Keybind.NOT_SET, config.saveClipHotkey());
        AtomicInteger saves = new AtomicInteger();
        ClipHotkey hotkey = new ClipHotkey(config::saveClipHotkey, saves::incrementAndGet);
        for (int code : new int[] { KeyEvent.VK_A, KeyEvent.VK_F7, KeyEvent.VK_SPACE })
        {
            hotkey.keyPressed(key(KeyEvent.KEY_PRESSED, code, 0));
        }
        assertEquals(0, saves.get());
    }

    /** No reflection: reads the plugin's source for the KeyManager wiring. */
    @Test
    public void thePluginRegistersTheHotkeyWithTheKeyManager() throws IOException
    {
        String source = new String(Files.readAllBytes(Paths.get("src", "main", "java", "com", "rfl", "RflPlugin.java")),
            StandardCharsets.UTF_8);
        assertTrue(source.contains("new ClipHotkey(config::saveClipHotkey, panel::saveClip)"));
        assertTrue(source.contains("keyManager.registerKeyListener(clipHotkey)"));
        assertTrue(source.contains("keyManager.unregisterKeyListener(clipHotkey)"));
        assertTrue(source.contains("private KeyManager keyManager"));
    }
}
