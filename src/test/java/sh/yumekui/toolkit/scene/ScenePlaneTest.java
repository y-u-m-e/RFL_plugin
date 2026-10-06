package sh.yumekui.toolkit.scene;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.Test;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

/**
 * {@link ScenePlane}: the plane comes from the local player's resolved world location, and is
 * null (defer) while there is no local player yet to read it from.
 */
public class ScenePlaneTest
{
    @Test
    public void readsThePlaneFromTheLocalPlayersWorldLocation()
    {
        Client client = fakeClient(fakePlayer(new WorldPoint(3200, 3200, 1)));
        assertEquals(Integer.valueOf(1), ScenePlane.settled(client));
    }

    @Test
    public void defersWhenThereIsNoLocalPlayerYet()
    {
        Client client = fakeClient(null);
        assertNull(ScenePlane.settled(client));
    }

    private static Player fakePlayer(WorldPoint at)
    {
        InvocationHandler handler = (proxy, method, args) ->
            "getWorldLocation".equals(method.getName()) && method.getParameterCount() == 0
                ? at
                : defaultFor(method.getReturnType());
        return (Player) Proxy.newProxyInstance(ScenePlaneTest.class.getClassLoader(),
            new Class<?>[] { Player.class }, handler);
    }

    private static Client fakeClient(Player local)
    {
        InvocationHandler handler = (proxy, method, args) ->
            "getLocalPlayer".equals(method.getName()) && method.getParameterCount() == 0
                ? local
                : defaultFor(method.getReturnType());
        return (Client) Proxy.newProxyInstance(ScenePlaneTest.class.getClassLoader(),
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
