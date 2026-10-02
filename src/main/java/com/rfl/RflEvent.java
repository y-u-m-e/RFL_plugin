package com.rfl;

/**
 * Wire event value type: {@code contact_start}, {@code contact_end}, {@code collision_seen} or
 * {@code plugin_toggle}.
 * Fields an event kind doesn't use stay {@code null}, so Gson leaves them out of the JSON.
 *
 * <p>Contacts are always between the local player and one other body, and carry no player name:
 * {@code contactId} matches a start to its end, {@code x}/{@code y}/{@code plane} is the world
 * tile under the touching triangles, and {@code depth} is the touching triangle-pair count (see
 * {@link ContactTracker}). {@code ball} is who held the handegg when the contact started:
 * {@code "self"} or {@code "other"}.
 *
 * <p>{@code collision_seen} is a name-free witness: two other players touching while one holds a
 * handegg, with only the tile.
 */
final class RflEvent
{
    long at;
    int tick;
    String type;
    Integer contactId;
    Integer x;
    Integer y;
    Integer plane;
    Integer depth;
    String ball;
    String plugin;
    Boolean enabled;

    private RflEvent()
    {
    }

    static RflEvent contactStart(long at, int tick, int contactId, int x, int y, int plane, int depth,
        String ball)
    {
        return contact("contact_start", at, tick, contactId, x, y, plane, depth, ball);
    }

    static RflEvent contactEnd(long at, int tick, int contactId, int x, int y, int plane, int depth,
        String ball)
    {
        return contact("contact_end", at, tick, contactId, x, y, plane, depth, ball);
    }

    static RflEvent collisionSeen(long at, int tick, int x, int y, int plane)
    {
        RflEvent e = new RflEvent();
        e.at = at;
        e.tick = tick;
        e.type = "collision_seen";
        e.x = x;
        e.y = y;
        e.plane = plane;
        return e;
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

    private static RflEvent contact(String type, long at, int tick, int contactId, int x, int y, int plane,
        int depth, String ball)
    {
        RflEvent e = new RflEvent();
        e.at = at;
        e.tick = tick;
        e.type = type;
        e.contactId = contactId;
        e.x = x;
        e.y = y;
        e.plane = plane;
        e.depth = depth;
        e.ball = ball;
        return e;
    }
}
