package com.rfl.contact;

import com.rfl.RflConfig;

import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.util.ColorUtil;

/**
 * Chat message for a finished collision, per the Collision chat message setting (off by default):
 * mirrors {@code IncompleteReporter}'s incomplete chat message exactly, a local game message
 * ({@link ChatMessageType#GAMEMESSAGE}) only this client's owner sees. Never typed into public,
 * clan or any other chat, and never sent to the server.
 *
 * <p>Call {@link #post} once per finished collision (where it's recorded, e.g.
 * {@code ContactDetector.saveFinished}), never per frame a collision stays open, so a long contact
 * posts exactly one message.
 *
 * <p>Client thread only.
 */
@Singleton
public final class CollisionChatMessage
{
    private final Client client;
    private final RflConfig config;

    @Inject
    CollisionChatMessage(Client client, RflConfig config)
    {
        this.client = client;
        this.config = config;
    }

    /** Posts once for {@code collision}, per the Collision chat message setting. A no-op while it's off. */
    public void post(Collision collision)
    {
        if (!config.collisionChatMessage())
        {
            return;
        }
        String label = ColorUtil.wrapWithColorTag("Collision:", config.contactHighlightColor());
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", label + " " + body(collision), null);
    }

    /**
     * Both names, the ball carrier first, matching how the panel bolds a lone carrier: "both"
     * holding, or neither, leaves them in the collision's own order with no "holding the handegg"
     * called out.
     */
    static String body(Collision collision)
    {
        boolean aHasBall = collision.ball.contains(collision.a);
        boolean bHasBall = collision.ball.contains(collision.b);
        boolean lone = aHasBall != bHasBall;
        String carrier = lone && bHasBall ? collision.b : collision.a;
        String other = lone && bHasBall ? collision.a : collision.b;
        return carrier + " collided with " + other + (lone ? " holding the handegg" : "");
    }
}
