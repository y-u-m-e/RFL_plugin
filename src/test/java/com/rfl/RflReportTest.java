package com.rfl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.google.gson.GsonBuilder;

public class RflReportTest
{
    @Test
    public void contactEventJsonOmitsPluginFields()
    {
        String json = new GsonBuilder().create().toJson(RflEvent.contactStart(1, 2, "A", "B", 34));
        assertFalse(json.contains("plugin"));
        assertTrue(json.contains("\"depth\":34"));
    }

    @Test
    public void jsonUsesSpecFieldNames()
    {
        RflReport report = new RflReport("Some Player", "install-1", 330, 1_000L, true,
            List.of("Player B"), List.of(new PluginEntry("GPU", "BUILTIN")), List.of());
        String json = new GsonBuilder().create().toJson(report);

        assertTrue(json.contains("\"v\":1"));
        assertTrue(json.contains("\"rsn\":\"Some Player\""));
        assertTrue(json.contains("\"installId\":\"install-1\""));
        assertTrue(json.contains("\"pluginVersion\":\"1.0.0\""));
        assertTrue(json.contains("\"world\":330"));
        assertTrue(json.contains("\"sentAt\":1000"));
        assertTrue(json.contains("\"inPoh\":true"));
        assertTrue(json.contains("\"seen\":[\"Player B\"]"));
        assertTrue(json.contains("\"plugins\":["));
        assertTrue(json.contains("\"events\":[]"));
    }
}
