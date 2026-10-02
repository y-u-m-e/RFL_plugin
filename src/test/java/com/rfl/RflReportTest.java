package com.rfl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.google.gson.GsonBuilder;

public class RflReportTest
{
    @Test
    public void contactEventJsonCarriesIdTileAndDepthButNoNames()
    {
        String json = new GsonBuilder().create().toJson(RflEvent.contactStart(1, 2, 7, 1890, 5730, 0, 34, "self"));
        assertTrue(json.contains("\"type\":\"contact_start\""));
        assertTrue(json.contains("\"contactId\":7"));
        assertTrue(json.contains("\"x\":1890"));
        assertTrue(json.contains("\"y\":5730"));
        assertTrue(json.contains("\"plane\":0"));
        assertTrue(json.contains("\"depth\":34"));
        assertTrue(json.contains("\"ball\":\"self\""));
        assertFalse(json.contains("plugin"));
        assertFalse(json.contains("\"a\""));
        assertFalse(json.contains("\"b\""));
    }

    @Test
    public void jsonUsesSpecFieldNames()
    {
        RflReport report = new RflReport("Some Player", "install-1", 330, 1_000L, true, "game-1",
            List.of(new PluginEntry("GPU", "BUILTIN")), List.of(), new RflReport.Features(true, true));
        String json = new GsonBuilder().create().toJson(report);

        assertTrue(json.contains("\"v\":1"));
        assertTrue(json.contains("\"rsn\":\"Some Player\""));
        assertTrue(json.contains("\"installId\":\"install-1\""));
        assertTrue(json.contains("\"pluginVersion\":\"1.0.0\""));
        assertTrue(json.contains("\"world\":330"));
        assertTrue(json.contains("\"sentAt\":1000"));
        assertTrue(json.contains("\"inPoh\":true"));
        assertTrue(json.contains("\"gameId\":\"game-1\""));
        assertTrue(json.contains("\"plugins\":["));
        assertTrue(json.contains("\"events\":[]"));
        assertTrue(json.contains("\"features\":{\"plugins\":true,\"contacts\":true}"));
        assertFalse(json.contains("seen"));
        assertFalse(json.contains("nearby"));
    }

    @Test
    public void gameIdEmptyWhenNotInAGame()
    {
        RflReport report = new RflReport("Some Player", "install-1", 330, 1_000L, true, "",
            List.of(new PluginEntry("GPU", "BUILTIN")), List.of(), new RflReport.Features(true, true));
        String json = new GsonBuilder().create().toJson(report);

        assertTrue(json.contains("\"gameId\":\"\""));
    }

    @Test
    public void disabledFeaturesBlankTheirFieldsAndAreReported()
    {
        RflReport report = new RflReport("Some Player", "install-1", 330, 1_000L, true, "game-1",
            List.of(new PluginEntry("GPU", "BUILTIN")), List.of(), new RflReport.Features(false, false));
        String json = new GsonBuilder().create().toJson(report);

        assertTrue(json.contains("\"plugins\":[]"));
        assertTrue(json.contains("\"features\":{\"plugins\":false,\"contacts\":false}"));
    }
}
