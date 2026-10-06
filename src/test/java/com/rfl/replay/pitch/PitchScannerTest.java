package com.rfl.replay.pitch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.rfl.replay.RecorderStats;
import com.rfl.replay.ReplaySampler;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * {@link PitchScanner}'s floor stage: the plane it puts in the {@code pitch} line comes from the
 * local player's resolved world location ({@code ScenePlane}), not the view, which can still read
 * the old scene's plane for a tick right after a LOADING reload; and the stage defers rather than
 * scan a guessed plane while there is no local player yet.
 */
public class PitchScannerTest
{
    private final List<Map<String, Object>> out = new ArrayList<>();
    private final PitchCapture capture = new PitchCapture(new PitchCapture.Sink()
    {
        @Override
        public void models(List<Map<String, Object>> lines)
        {
        }

        @Override
        public void pitch(Map<String, Object> line)
        {
            out.add(line);
        }

        @Override
        public void locs(Map<String, Object> line)
        {
            out.add(line);
        }
    }, 1_000_000L, System::nanoTime);

    private final PitchScanner scanner = new PitchScanner(capture, new RecorderStats());

    @Test
    public void defersTheFloorStageWhileThereIsNoLocalPlayer()
    {
        // The view reports some plane regardless, as it would right after LOADING, before the
        // client has settled: the scan must not trust it while there is nobody to read it from.
        Client client = fakeClient(null, fakeWorldView(0));
        scanner.start();
        for (int i = 0; i < 3; i++)
        {
            scanner.step(client, 1, new ReplaySampler());
        }
        assertTrue("stays queued, never guesses a plane and finishes", scanner.scanning());
        assertTrue("nothing handed to the capture while deferring", out.isEmpty());
    }

    @Test
    public void takesThePlaneFromTheLocalPlayerNotTheStaleView()
    {
        // The view still reads the old scene's plane (0); the player is really on plane 1, as on
        // the first tick after a POH reload settles on the ground floor.
        Player local = fakePlayer(new WorldPoint(3200, 3200, 1), new LocalPoint(6400, 6400));
        Client client = fakeClient(local, fakeWorldView(0));

        scanner.start();
        scanner.step(client, 411183, new ReplaySampler()); // READ_FLOOR
        scanner.step(client, 411183, new ReplaySampler()); // READ_OBJECTS
        scanner.step(client, 411183, new ReplaySampler()); // FINISH -> capture.begin
        capture.step(); // the capture writes the (house-model-free) pitch line at once

        assertEquals(1, out.size());
        assertEquals(1, out.get(0).get("plane"));
    }

    @Test
    public void resumesOnceTheLocalPlayerAppears()
    {
        WorldView view = fakeWorldView(0);
        Client[] client = { fakeClient(null, view) };
        scanner.start();
        scanner.step(client[0], 1, new ReplaySampler());
        assertTrue("still waiting on a local player", scanner.scanning());
        assertTrue(out.isEmpty());

        client[0] = fakeClient(fakePlayer(new WorldPoint(3200, 3200, 1), new LocalPoint(6400, 6400)), view);
        scanner.step(client[0], 1, new ReplaySampler()); // READ_FLOOR, now settled
        scanner.step(client[0], 1, new ReplaySampler()); // READ_OBJECTS
        scanner.step(client[0], 1, new ReplaySampler()); // FINISH
        capture.step();

        assertEquals(1, out.size());
        assertEquals(1, out.get(0).get("plane"));
    }

    private static Player fakePlayer(WorldPoint worldLocation, LocalPoint localLocation)
    {
        InvocationHandler handler = (proxy, method, args) ->
        {
            switch (method.getName())
            {
                case "getWorldLocation":
                    return worldLocation;
                case "getLocalLocation":
                    return localLocation;
                default:
                    return defaultFor(method.getReturnType());
            }
        };
        return (Player) Proxy.newProxyInstance(PitchScannerTest.class.getClassLoader(),
            new Class<?>[] { Player.class }, handler);
    }

    private static WorldView fakeWorldView(int plane)
    {
        InvocationHandler handler = (proxy, method, args) ->
            "getPlane".equals(method.getName()) && method.getParameterCount() == 0
                ? plane
                : defaultFor(method.getReturnType());
        return (WorldView) Proxy.newProxyInstance(PitchScannerTest.class.getClassLoader(),
            new Class<?>[] { WorldView.class }, handler);
    }

    private static Client fakeClient(Player local, WorldView view)
    {
        InvocationHandler handler = (proxy, method, args) ->
        {
            switch (method.getName())
            {
                case "getLocalPlayer":
                    return local;
                case "getTopLevelWorldView":
                    return view;
                default:
                    return defaultFor(method.getReturnType());
            }
        };
        return (Client) Proxy.newProxyInstance(PitchScannerTest.class.getClassLoader(),
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
