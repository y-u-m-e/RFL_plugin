package com.rfl;

/**
 * Wire event value type: {@code contact_start}, {@code contact_end} or {@code plugin_toggle}.
 * Fields an event kind doesn't use stay {@code null}, so Gson leaves them out of the JSON.
 * {@code depth} is the touching triangle-pair count (see {@link ContactTracker}).
 */
final class RflEvent
{
    long at;
    int tick;
    String type;
    String a;
    String b;
    Integer depth;
    String plugin;
    Boolean enabled;

    private RflEvent()
    {
    }

    static RflEvent contactStart(long at, int tick, String a, String b, int depth)
    {
        return contact("contact_start", at, tick, a, b, depth);
    }

    static RflEvent contactEnd(long at, int tick, String a, String b, int depth)
    {
        return contact("contact_end", at, tick, a, b, depth);
    }

    static RflEvent pluginToggle(long at, int tick, String plugin, boolean enabled)
    {
        RflEvent e = new RflEvent();
        e.at = at;
        e.tick = tick;
        e.type = "plugin_toggle";
        e.plugin = plugin;
        e.enabled = enabled;
        return e;
    }

    private static RflEvent contact(String type, long at, int tick, String a, String b, int depth)
    {
        RflEvent e = new RflEvent();
        e.at = at;
        e.tick = tick;
        e.type = type;
        e.a = a;
        e.b = b;
        e.depth = depth;
        return e;
    }
}
