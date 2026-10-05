package sh.yumekui.toolkit.scene;

import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Singleton;

/**
 * Which loaded scene a recorded scene tile ({@code sx,sy}) belongs to. Every scene load, hop or
 * logout starts a new epoch; a stamp is the epoch plus the plane the player was on. A tile is only
 * drawn again while its stamp equals the current one, so a collision from a rebuilt scene (where
 * the same scene coordinates may be a different tile) is never drawn on the wrong tile.
 *
 * <p>Threads: any; the epoch is atomic.
 */
@Singleton
public final class SceneStamps
{
    /** No stamp: never drawable. */
    public static final int NONE = -1;

    private final AtomicInteger epoch = new AtomicInteger();

    /** A new scene: every earlier stamp goes stale. */
    public void bump()
    {
        epoch.incrementAndGet();
    }

    /** The stamp for the current scene on {@code plane} (0-3). */
    public int stamp(int plane)
    {
        return stamp(epoch.get(), plane);
    }

    static int stamp(int epoch, int plane)
    {
        return (epoch & 0x0fffffff) << 2 | (plane & 3);
    }

    /** Whether a tile stamped {@code stamp} is still the same tile in the scene stamped {@code current}. */
    public static boolean valid(int stamp, int current)
    {
        return stamp != NONE && stamp == current;
    }
}
