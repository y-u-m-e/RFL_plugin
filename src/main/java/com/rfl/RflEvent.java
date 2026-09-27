package com.rfl;

/**
 * Wire event value type. Nullable fields are left {@code null} for the event kinds that
 * don't use them so an injected Gson (Task 5) omits them from the serialized JSON.
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
