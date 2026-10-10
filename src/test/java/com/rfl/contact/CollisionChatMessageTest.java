package com.rfl.contact;

import static com.rfl.Fixtures.mesh;
import static com.rfl.Fixtures.WALL;
import static com.rfl.Fixtures.THROUGH;
import static com.rfl.Fixtures.FAR;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.rfl.Fixtures;
import com.rfl.RflConfig;
import sh.yumekui.toolkit.geom.TriangleMesh;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.util.ColorUtil;

/**
 * {@link CollisionChatMessage}: mirrors the incomplete chat message exactly, off by default, posted
 * once per finished collision rather than once per frame it stays open.
 */
public class CollisionChatMessageTest
{
    @Test
    public void namesTheLoneCarrierFirst()
    {
        Collision collision = Fixtures.collision("Amy", "Bob").holders("Bob").build();
        assertEquals("Bob collided with Amy holding the handegg", CollisionChatMessage.body(collision));
    }

    @Test
    public void bothHoldingLeavesTheCollisionsOwnOrderWithNoCallout()
    {
        Collision collision = Fixtures.collision("Amy", "Bob").holders("Amy", "Bob").build();
        assertEquals("Amy collided with Bob", CollisionChatMessage.body(collision));
    }

    @Test
    public void neitherHoldingAlsoLeavesTheCollisionsOwnOrder()
    {
        Collision collision = Fixtures.collision("Amy", "Bob").holders().build();
        assertEquals("Amy collided with Bob", CollisionChatMessage.body(collision));
    }

    @Test
    public void nothingPostedWhileTheSettingIsOff()
    {
        Collision collision = Fixtures.collision("Amy", "Bob").holders("Bob").build();
        List<Object[]> calls = new ArrayList<>();

        new CollisionChatMessage(fakeClient(calls), configWith(false)).post(collision);

        assertTrue("nothing posted while the setting is off", calls.isEmpty());
    }

    @Test
    public void postsALocalGameMessageWhenOn()
    {
        Collision collision = Fixtures.collision("Amy", "Bob").holders("Bob").build();
        List<Object[]> calls = new ArrayList<>();

        new CollisionChatMessage(fakeClient(calls), configWith(true)).post(collision);

        assertEquals(1, calls.size());
        Object[] call = calls.get(0);
        assertEquals(ChatMessageType.GAMEMESSAGE, call[0]);
        assertEquals("", call[1]);
        String expected = ColorUtil.wrapWithColorTag("Collision:", new RflConfig()
        {
        }.contactHighlightColor()) + " Bob collided with Amy holding the handegg";
        assertEquals(expected, call[2]);
        assertNull(call[3]);
    }

    @Test
    public void postsOncePerFinishedCollisionNotOncePerFrameItStaysOpen()
    {
        ContactTracker tracker = new ContactTracker((x, y) -> new TileRef(0, 0, 0, 0, 0), () -> 330);
        List<Object[]> calls = new ArrayList<>();
        CollisionChatMessage chat = new CollisionChatMessage(fakeClient(calls), configWith(true));

        TriangleMesh a = mesh(WALL);
        // Several frames of the same contact staying open: no message yet, however many times it's
        // sampled, so a long contact doesn't spam chat.
        for (int frame = 0; frame < 5; frame++)
        {
            tracker.update(Map.of("A", a, "B", mesh(THROUGH)), Set.of("B"), 1000 + frame, frame, false);
            for (Collision c : tracker.takeFinished())
            {
                chat.post(c);
            }
        }
        assertTrue("no message while the collision is still open", calls.isEmpty());

        // Bounds separate: the collision finishes, exactly once.
        tracker.update(Map.of("A", a, "B", mesh(FAR)), Set.of(), 2000, 10, false);
        for (Collision c : tracker.takeFinished())
        {
            chat.post(c);
        }
        assertEquals("exactly one message for the whole contact", 1, calls.size());
    }

    private static RflConfig configWith(boolean collisionChatMessage)
    {
        return new RflConfig()
        {
            @Override
            public boolean collisionChatMessage()
            {
                return collisionChatMessage;
            }
        };
    }

    /** Fakes {@link Client}, recording each {@code addChatMessage} call's arguments. */
    private static Client fakeClient(List<Object[]> calls)
    {
        InvocationHandler handler = (proxy, method, args) ->
        {
            if ("addChatMessage".equals(method.getName()))
            {
                calls.add(args);
                return null;
            }
            return defaultFor(method.getReturnType());
        };
        return (Client) Proxy.newProxyInstance(CollisionChatMessageTest.class.getClassLoader(),
            new Class<?>[] { Client.class }, handler);
    }

    private static Object defaultFor(Class<?> returnType)
    {
        if (!returnType.isPrimitive() || returnType == void.class)
        {
            return null;
        }
        if (returnType == boolean.class)
        {
            return false;
        }
        if (returnType == char.class)
        {
            return (char) 0;
        }
        if (returnType == double.class)
        {
            return 0d;
        }
        if (returnType == float.class)
        {
            return 0f;
        }
        if (returnType == long.class)
        {
            return 0L;
        }
        return 0;
    }
}
