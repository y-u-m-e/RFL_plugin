package com.rfl;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Body fitting against real posed OSRS player models, dumped from the client with Debug logging
 * (ModelDumper). OSRS models are low-poly: a leg is a few rings of vertices, so these catch fits
 * that pass on idealised test figures but go wrong on the real thing.
 */
public class RealModelBodyTest
{
    private static final class Dump
    {
        final JsonObject json;
        final Body body;

        Dump(String name)
        {
            json = new Gson().fromJson(new InputStreamReader(
                RealModelBodyTest.class.getResourceAsStream("dumps/" + name + ".json"), StandardCharsets.UTF_8),
                JsonObject.class);
            float[] xs = floats("xs");
            body = Body.from(xs, floats("ys"), floats("zs"), xs.length, json.get("orientation").getAsInt(),
                json.get("baseX").getAsInt(), json.get("baseY").getAsInt());
        }

        float[] floats(String key)
        {
            JsonArray a = json.getAsJsonArray(key);
            float[] out = new float[a.size()];
            for (int i = 0; i < out.length; i++)
            {
                out[i] = a.get(i).getAsFloat();
            }
            return out;
        }

        Capsule part(String name)
        {
            for (Capsule c : body.parts)
            {
                if (c.name.equals(name))
                {
                    return c;
                }
            }
            return null;
        }
    }

    /** Degrees between the capsule axis and straight up (0 = upright, 90 = lying flat). */
    private static double tilt(Capsule c)
    {
        double horizontal = Math.hypot(c.bx - c.ax, c.by - c.ay);
        return Math.toDegrees(Math.atan2(horizontal, Math.abs(c.bz - c.az)));
    }

    private static double length(Capsule c)
    {
        return Math.sqrt(Math.pow(c.bx - c.ax, 2) + Math.pow(c.by - c.ay, 2) + Math.pow(c.bz - c.az, 2));
    }

    private static double top(Capsule c)
    {
        return Math.max(c.az, c.bz);
    }

    private static double bottom(Capsule c)
    {
        return Math.min(c.az, c.bz);
    }

    @Test
    public void standingLegsAreUprightThighOverShin()
    {
        Dump d = new Dump("standing");
        for (String side : new String[]{"left", "right"})
        {
            Capsule thigh = d.part(side + "Thigh");
            Capsule shin = d.part(side + "Shin");
            assertNotNull(side + "Thigh", thigh);
            assertNotNull(side + "Shin", shin);
            assertTrue(side + " thigh tilt " + tilt(thigh), tilt(thigh) < 35);
            assertTrue(side + " shin tilt " + tilt(shin), tilt(shin) < 35);
            assertTrue(side + " thigh above shin", top(thigh) > top(shin));
            assertTrue(side + " thigh reaches the hips " + top(thigh), top(thigh) > 55);
            // The foot covers the lowest part of the leg; the shin reaches down to meet it.
            assertTrue(side + " shin reaches the ankle " + bottom(shin), bottom(shin) < 30);
        }
    }

    @Test
    public void movingLegsStayLegShaped()
    {
        for (String pose : new String[]{"running", "walking"})
        {
            Dump d = new Dump(pose);
            for (String side : new String[]{"left", "right"})
            {
                Capsule thigh = d.part(side + "Thigh");
                Capsule shin = d.part(side + "Shin");
                assertNotNull(pose + " " + side + "Thigh", thigh);
                assertNotNull(pose + " " + side + "Shin", shin);
                // A running stride kicks the rear leg back: about 65 degrees in the real running pose.
                assertTrue(pose + " " + side + " thigh tilt " + tilt(thigh), tilt(thigh) < 75);
                assertTrue(pose + " " + side + " thigh above shin", top(thigh) > top(shin));
                for (Capsule c : new Capsule[]{thigh, shin})
                {
                    assertTrue(pose + " " + c.name + " length " + length(c), length(c) > 10 && length(c) < 80);
                }
            }
        }
    }

    @Test
    public void standingFeetLieFlat()
    {
        // Only standing feet are flat: walking lifts the heel (about 38 degrees in the real dump)
        // and running kicks the rear foot back sole-up.
        Dump d = new Dump("standing");
        for (String side : new String[]{"left", "right"})
        {
            Capsule foot = d.part(side + "Foot");
            assertNotNull(side + "Foot", foot);
            assertTrue(side + " foot tilt " + tilt(foot), tilt(foot) > 60);
        }
    }

    @Test
    public void partThicknessDoesNotChangeWithThePose()
    {
        Dump standing = new Dump("standing");
        for (String pose : new String[]{"running", "walking"})
        {
            Dump moving = new Dump(pose);
            for (Capsule c : standing.body.parts)
            {
                Capsule m = moving.part(c.name);
                if (m != null)
                {
                    assertTrue(pose + " " + c.name + " radius " + c.radius + " vs " + m.radius,
                        Math.abs(c.radius - m.radius) < 0.5);
                }
            }
        }
    }

    @Test
    public void standingArmsHangDown()
    {
        Dump d = new Dump("standing");
        for (String side : new String[]{"left", "right"})
        {
            for (String part : new String[]{"UpperArm", "Forearm"})
            {
                Capsule c = d.part(side + part);
                assertNotNull(side + part, c);
                assertTrue(side + part + " tilt " + tilt(c), tilt(c) < 35);
            }
            assertTrue(side + " upper arm above forearm",
                top(d.part(side + "UpperArm")) > top(d.part(side + "Forearm")));
        }
    }

    @Test
    public void feetAreAsLongAndThickAsTheFoot()
    {
        // Real standing right foot: vertices span about 37 units heel to toe and 12 high.
        Capsule foot = new Dump("standing").part("rightFoot");
        double total = length(foot) + 2 * foot.radius; // the rounded caps add a radius at each end
        assertTrue("foot total length " + total, total > 30 && total < 42);
        assertTrue("foot radius " + foot.radius, foot.radius <= 6.5);
    }

    @Test
    public void raisedFootStillGetsAFootPart()
    {
        // In the real running dump the right foot is kicked up behind, well off the ground.
        Dump d = new Dump("running");
        Capsule foot = d.part("rightFoot");
        assertNotNull("rightFoot", foot);
        assertTrue("raised foot is off the ground " + bottom(foot), bottom(foot) > 20);
        Capsule shin = d.part("rightShin");
        assertTrue("shin sits above the raised foot", top(shin) > top(foot));
    }
}
