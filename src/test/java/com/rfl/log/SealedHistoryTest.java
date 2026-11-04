package com.rfl.log;

import sh.yumekui.toolkit.crypto.SealedBox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/** {@link SealedHistory}: the RFL1 format the website opens, built only from memory. */
public class SealedHistoryTest
{
    private static final Gson GSON = new GsonBuilder().create();
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    private static final long T = LocalDateTime.of(2026, 11, 3, 12, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
    private static final long MIN = 60_000L;

    private static JsonObject vector() throws IOException
    {
        try (InputStream in = SealedHistoryTest.class.getResourceAsStream("rfl1-test-vector.json"))
        {
            return GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        }
    }

    private static byte[] hex(String s)
    {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** What rfl.gg does: strip the prefix, open, inflate, parse. */
    static JsonObject open(String blob, byte[] ownerPriv) throws GeneralSecurityException, DataFormatException
    {
        assertTrue(blob.startsWith(SealedHistory.PREFIX));
        byte[] deflated = SealedBox.open(B64.decode(blob.substring(SealedHistory.PREFIX.length())), ownerPriv,
            "RFL1".getBytes(StandardCharsets.US_ASCII));
        return GSON.fromJson(inflate(deflated), JsonObject.class);
    }

    private static String inflate(byte[] deflated) throws DataFormatException
    {
        Inflater inflater = new Inflater(true);
        inflater.setInput(deflated);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!inflater.finished())
        {
            int n = inflater.inflate(buffer);
            if (n == 0 && inflater.needsInput())
            {
                break;
            }
            out.write(buffer, 0, n);
        }
        inflater.end();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Deflate output isn't canonical: the vector's encoder (not zlib) picks other Huffman tables
     * than java.util.zip at any level, so the whole blob can't match. Everything else does, byte for
     * byte: the JSON the vector seals, and the sealing of the vector's own deflated bytes.
     */
    @Test
    public void matchesTheTestVector() throws Exception
    {
        JsonObject v = vector();
        byte[] ownerPub = B64.decode(v.get("ownerPublicKey").getAsString());
        byte[] ownerPriv = B64.decode(v.get("ownerPrivateKey").getAsString());
        byte[] ephemeral = B64.decode(v.get("ephemeralPrivateKey").getAsString());
        byte[] nonce = hex(v.get("nonceHex").getAsString());
        String expected = v.get("blob").getAsString();
        byte[] theirDeflated = SealedBox.open(B64.decode(expected.substring(SealedHistory.PREFIX.length())),
            ownerPriv, "RFL1".getBytes(StandardCharsets.US_ASCII));

        // The crypto and the framing: exact.
        assertEquals(expected, SealedHistory.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
            SealedBox.seal(theirDeflated, ownerPub, "RFL1".getBytes(StandardCharsets.US_ASCII), ephemeral, nonce)));

        // The envelope JSON: exact, field order and unescaped "&" included.
        JsonObject e = v.getAsJsonObject("envelope");
        SealedHistory.Envelope envelope = new SealedHistory.Envelope(e.get("rsn").getAsString(),
            e.get("world").getAsInt(), e.get("at").getAsLong(), e.get("plugin").getAsString(),
            e.get("text").getAsString());
        Gson plain = GSON.newBuilder().disableHtmlEscaping().create();
        String ours = SealedHistory.seal(plain, envelope, ownerPub, ephemeral, nonce);
        assertEquals(inflate(theirDeflated), inflate(SealedBox.open(B64.decode(ours.substring(5)), ownerPriv,
            "RFL1".getBytes(StandardCharsets.US_ASCII))));
        // Same ephemeral key and nonce up front.
        assertEquals(expected.substring(0, 5 + 56), ours.substring(0, 5 + 56));
    }

    @Test
    public void ownerKeyInTheVectorIsThePublicHalf() throws Exception
    {
        JsonObject v = vector();
        assertEquals(v.get("ownerPublicKey").getAsString(), Base64.getUrlEncoder().withoutPadding()
            .encodeToString(SealedBox.publicKey(B64.decode(v.get("ownerPrivateKey").getAsString()))));
    }

    @Test
    public void parseKeyAcceptsOnly32BytesOfBase64url()
    {
        assertNotNull(SealedHistory.parseKey("9MRwbGGJod1aX4P_tNPNT-z_114HLH2rnjx76hw44XM"));
        assertNotNull(SealedHistory.parseKey("  9MRwbGGJod1aX4P_tNPNT-z_114HLH2rnjx76hw44XM \n"));
        assertNull(SealedHistory.parseKey("9MRwbGGJod1aX4P/tNPNT+z/114HLH2rnjx76hw44XM"));
        assertNull(SealedHistory.parseKey("9MRwbGGJod1aX4P_tNPNT-z_114HLH2rnjx76hw44X"));
        assertNull(SealedHistory.parseKey("not a key"));
        assertNull(SealedHistory.parseKey("AAAA"));
    }

    @Test
    public void anInvalidKeyCopiesNothing()
    {
        List<String> lines = List.of(toggle(T, "Ammo", true));
        for (String key : new String[] { "not a key", "AAAA", "9MRwbGGJod1aX4P/tNPNT+z/114HLH2rnjx76hw44XM",
            // 32 zero bytes: the right length, but a low-order point no shared secret comes from
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" })
        {
            SealedHistory.Copy copy = SealedHistory.copy(GSON, key, lines, live("Agility"), "Ref Bob", 354, T,
                ZoneOffset.UTC);
            assertNull(key, copy.getText());
            assertEquals("Copy failed: invalid encryption key", copy.getMessage());
        }
        SealedHistory.Copy ok = SealedHistory.copy(GSON, "9MRwbGGJod1aX4P_tNPNT-z_114HLH2rnjx76hw44XM", lines,
            live("Agility"), "Ref Bob", 354, T, ZoneOffset.UTC);
        // Wrapped in a Discord code block so markdown can't eat the blob's underscores.
        assertTrue(ok.getText().startsWith("```\nRFL1:"));
        assertTrue(ok.getText().endsWith("\n```"));
        assertTrue(ok.getText().length() <= PluginHistory.DISCORD_LIMIT);
        assertTrue(ok.getMessage().startsWith("Copied encrypted history (0 visits, "));
    }

    private static List<PluginEntry> live(String... on)
    {
        List<PluginEntry> list = new ArrayList<>();
        list.add(new PluginEntry("Off One", false, PluginEntry.BUILTIN));
        for (String name : on)
        {
            list.add(new PluginEntry(name, true, name.startsWith("Side") ? PluginEntry.SIDELOADED : PluginEntry.HUB));
        }
        return list;
    }

    private static String enter(long at, List<PluginEntry> plugins)
    {
        return GSON.toJson(new PluginLog.Snapshot(at, "Ref Bob", 354, plugins, "enter"));
    }

    private static String leave(long at, List<PluginEntry> plugins)
    {
        return GSON.toJson(new PluginLog.Snapshot(at, "Ref Bob", 354, plugins, "leave"));
    }

    private static String toggle(long at, String name, boolean on)
    {
        return GSON.toJson(new PluginLog.Toggle(at, "Ref Bob", 354, name, on));
    }

    private static byte[] ownerPriv()
    {
        byte[] priv = new byte[32];
        priv[0] = 42;
        priv[31] = 7;
        return priv;
    }

    @Test
    public void roundTripsTheSessionTextMarkedLiveOnly() throws Exception
    {
        List<PluginEntry> plugins = live("Agility", "Side Thing", "Bank");
        List<String> lines = List.of(enter(T, plugins), toggle(T + MIN, "Ammo", true), leave(T + 5 * MIN, plugins));
        SealedHistory.Result result = SealedHistory.build(GSON, lines, live("Agility", "Bank"), "Ref Bob", 354,
            T + 6 * MIN, ZoneOffset.UTC, SealedBox.publicKey(ownerPriv()), PluginHistory.DISCORD_LIMIT);
        assertEquals(1, result.getVisits());
        assertEquals(1, result.getShown());
        JsonObject envelope = open(result.getBlob(), ownerPriv());
        assertEquals("[v, rsn, world, at, plugin, text]", envelope.keySet().toString());
        assertEquals(354, envelope.get("world").getAsInt());
        assertEquals(T + 6 * MIN, envelope.get("at").getAsLong());
        String text = envelope.get("text").getAsString();
        assertEquals("RFL plugin history - Ref Bob - W354 - live session only", text.split("\n")[0]);
        assertTrue(text, text.contains("Now on (2): Agility, Bank\n"));
        assertTrue(text, text.contains("On (3): Agility, Bank, Side Thing*\n  +Ammo 12:01:00"));
        assertFalse("not code-fenced", text.contains("```"));
        assertEquals("Copied encrypted history (1 visit, " + result.getBlob().length() + " chars)",
            SealedHistory.copiedMessage(result));
    }

    @Test
    public void anEmptySessionStillCopiesTheLiveList() throws Exception
    {
        SealedHistory.Result result = SealedHistory.build(GSON, List.of(), live("Agility"), "Ref Bob", 0, T,
            ZoneOffset.UTC, SealedBox.publicKey(ownerPriv()), PluginHistory.DISCORD_LIMIT);
        String text = open(result.getBlob(), ownerPriv()).get("text").getAsString();
        assertTrue(text, text.startsWith("RFL plugin history - Ref Bob - live session only\n2026-11-03 12:00:00 to "
            + "12:00:00 - 0 house visits\nNow on (1): Agility"));
    }

    /** Many visits with distinct lists: the oldest go until the blob fits Discord, and the text says how many. */
    @Test
    public void dropsOldestVisitsToFitDiscord() throws Exception
    {
        List<String> lines = new ArrayList<>();
        java.util.Random random = new java.util.Random(11);
        for (int i = 0; i < 40; i++)
        {
            // Random names, so deflate can't squeeze 40 visits into one message.
            List<PluginEntry> plugins = live(Long.toHexString(random.nextLong()), Long.toHexString(random.nextLong()),
                Long.toHexString(random.nextLong()));
            lines.add(enter(T + i * 10 * MIN, plugins));
            lines.add(toggle(T + i * 10 * MIN + MIN, "Toggle " + i, true));
            lines.add(leave(T + i * 10 * MIN + 5 * MIN, plugins));
        }
        long start = System.nanoTime();
        SealedHistory.Result result = SealedHistory.build(GSON, lines, live("Agility"), "Ref Bob", 354,
            T + 500 * MIN, ZoneOffset.UTC, SealedBox.publicKey(ownerPriv()), PluginHistory.DISCORD_LIMIT);
        System.out.println("RFL1 trimmed build: " + (System.nanoTime() - start) / 1_000_000 + " ms, shown "
            + result.getShown() + "/" + result.getVisits() + ", " + result.getBlob().length() + " chars");
        assertTrue(result.getBlob().length() <= PluginHistory.DISCORD_LIMIT);
        assertEquals(40, result.getVisits());
        assertTrue(result.getShown() > 0 && result.getShown() < 40);
        String text = open(result.getBlob(), ownerPriv()).get("text").getAsString();
        int dropped = 40 - result.getShown();
        assertTrue(text, text.contains("… " + dropped + " more visits (older, not shown)"));
        assertTrue("newest kept", text.contains("Toggle 39"));
        assertFalse("oldest dropped", text.contains("+Toggle 0 "));
    }

    /** Typical copy cost: one 97-plugin visit, warmed up. */
    @Test
    public void typicalCopyIsFast() throws Exception
    {
        JsonObject v = vector();
        List<PluginEntry> plugins = new ArrayList<>();
        for (String name : v.getAsJsonObject("envelope").get("text").getAsString().split("\n")[5]
            .replaceFirst("^On \\(\\d+\\): ", "").split(", "))
        {
            plugins.add(new PluginEntry(name, true, PluginEntry.HUB));
        }
        List<String> lines = List.of(enter(T, plugins), toggle(T + MIN, "Ammo", true));
        byte[] pub = SealedBox.publicKey(ownerPriv());
        SealedHistory.Result result = null;
        for (int i = 0; i < 50; i++)
        {
            result = SealedHistory.build(GSON, lines, plugins, "Ref Bob", 354, T + 2 * MIN, ZoneOffset.UTC, pub,
                PluginHistory.DISCORD_LIMIT);
        }
        int runs = 200;
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++)
        {
            SealedHistory.build(GSON, lines, plugins, "Ref Bob", 354, T + 2 * MIN, ZoneOffset.UTC, pub,
                PluginHistory.DISCORD_LIMIT);
        }
        long micros = (System.nanoTime() - start) / 1000 / runs;
        System.out.println("RFL1 typical build: " + micros + " us per copy, " + result.getBlob().length() + " chars");
        assertEquals(97, plugins.size());
        assertTrue(result.getBlob().length() <= PluginHistory.DISCORD_LIMIT);
    }

    /** The session comes from memory: editing or deleting the day file changes nothing. */
    @Test
    public void sessionLinesNeverReadTheDayFile() throws Exception
    {
        Path dir = Files.createTempDirectory("rfl-plugins");
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try
        {
            PluginLog log = new PluginLog(GSON, executor, dir);
            log.snapshot(T, "Ref Bob", 354, live("Agility"), PluginAudit.ENTER);
            log.toggle(T + MIN, "Ref Bob", 354, "Ammo", true);
            executor.submit(() -> null).get(5, TimeUnit.SECONDS);
            Path day = log.dayFile(T);
            for (int i = 0; i < 50 && !Files.isRegularFile(day); i++)
            {
                Thread.sleep(20);
            }
            Files.write(day, (toggle(T + 2 * MIN, "Edited In", true) + "\n").getBytes(StandardCharsets.UTF_8));
            List<String> lines = log.sessionLines();
            Files.delete(day);
            assertEquals(lines, log.sessionLines());
            assertEquals(2, lines.size());
            String text = open(SealedHistory.build(GSON, lines, live("Agility"), "Ref Bob", 354, T + 3 * MIN,
                ZoneOffset.UTC, SealedBox.publicKey(ownerPriv()), PluginHistory.DISCORD_LIMIT).getBlob(), ownerPriv())
                .get("text").getAsString();
            assertTrue(text, text.contains("+Ammo"));
            assertFalse(text, text.contains("Edited In"));
        }
        finally
        {
            executor.shutdownNow();
        }
    }
}
