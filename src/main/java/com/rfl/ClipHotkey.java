package com.rfl;

import java.util.function.Supplier;

import net.runelite.client.config.Keybind;
import net.runelite.client.util.HotkeyListener;

/**
 * The Save replay clip hotkey: RuneLite's {@link HotkeyListener} on the configured {@link Keybind}
 * (unset by default, so it never fires until the user picks a key), running {@code onPress} once
 * per press. Registered with the client's KeyManager while the plugin runs.
 *
 * <p>Key events arrive on the EDT; {@code onPress} hands the work to the client thread itself.
 */
final class ClipHotkey extends HotkeyListener
{
    private final Runnable onPress;

    ClipHotkey(Supplier<Keybind> keybind, Runnable onPress)
    {
        super(keybind);
        this.onPress = onPress;
    }

    @Override
    public void hotkeyPressed()
    {
        onPress.run();
    }
}
