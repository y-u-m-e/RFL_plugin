package com.rfl.log;

import sh.yumekui.toolkit.crypto.SealedBox;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;

import com.google.gson.Gson;

import lombok.Value;

/**
 * The encrypted Copy plugin history: {@code "RFL1:"} + base64url (no padding) of a
 * {@link SealedBox} (HKDF info {@code "RFL1"}) holding the raw-deflated UTF-8 JSON {@link Envelope}.
 * Only the league admin's private key opens it, on rfl.gg, and an edited blob fails to open.
 * It shows casual edits, not a player who writes their own encoder with the public key.
 *
 * <p>No IO, no Swing; any thread off the client thread.
 */
public final class SealedHistory
{
    public static final String PREFIX = "RFL1:";
    private static final byte[] INFO = "RFL1".getBytes(StandardCharsets.US_ASCII);
    /** The plugin version in the envelope; keep in step with build.gradle's version. */
    public static final String PLUGIN_VERSION = "1.0";

    /** The sealed JSON. Field order is the wire order. */
    static final class Envelope
    {
        final int v = 1;
        final String rsn;
        final int world;
        final long at;
        final String plugin;
        final String text;

        Envelope(String rsn, int world, long at, String plugin, String text)
        {
            this.rsn = rsn;
            this.world = world;
            this.at = at;
            this.plugin = plugin;
            this.text = text;
        }
    }

    public static final String INVALID_KEY = "Copy failed: invalid encryption key";
    public static final String TOO_LONG = "Copy failed: too long for one Discord message";

    /** What a copy puts on the clipboard (null for nothing) and the panel's message. */
    @Value
    public static class Copy
    {
        String text;
        String message;
    }

    /**
     * The encrypted copy for a set {@code keySetting}: never plain text. An invalid key copies
     * nothing and says so.
     */
    public static Copy copy(Gson gson, String keySetting, List<String> sessionLines, List<PluginEntry> live,
        String rsn, int world, long nowMs, ZoneId zone)
    {
        byte[] key = parseKey(keySetting);
        if (key == null)
        {
            return new Copy(null, INVALID_KEY);
        }
        Result result;
        try
        {
            result = build(gson, sessionLines, live, rsn, world, nowMs, zone, key, PluginHistory.DISCORD_LIMIT);
        }
        catch (GeneralSecurityException e)
        {
            // A low-order point or similar: 32 bytes, but not a usable key.
            return new Copy(null, INVALID_KEY);
        }
        return result == null ? new Copy(null, TOO_LONG) : new Copy(result.getBlob(), copiedMessage(result));
    }

    /** The blob, and how many of the session's visits it shows. */
    @Value
    public static class Result
    {
        String blob;
        int visits;
        int shown;
    }

    private SealedHistory()
    {
    }

    /**
     * The 32-byte public key from the setting, or null when it isn't 32 bytes of base64url.
     * Callers check {@link String#isEmpty()} first: empty means plain text.
     */
    public static byte[] parseKey(String setting)
    {
        try
        {
            byte[] key = Base64.getUrlDecoder().decode(setting.trim());
            return key.length == SealedBox.KEY_BYTES ? key : null;
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    /**
     * Builds the session's text from memory and seals it, dropping the oldest visits until the blob
     * fits {@code limit} (Discord's 2000). Null when even no visits don't fit.
     */
    public static Result build(Gson gson, List<String> sessionLines, List<PluginEntry> live, String rsn, int world,
        long nowMs, ZoneId zone, byte[] ownerPub, int limit) throws GeneralSecurityException
    {
        Gson plain = gson.newBuilder().disableHtmlEscaping().create();
        int max = Integer.MAX_VALUE;
        while (true)
        {
            PluginHistory.Result text = PluginHistory.session(gson, sessionLines, live, rsn, world, nowMs, zone, max);
            String blob = seal(plain, new Envelope(rsn == null ? "" : rsn, world, nowMs, PLUGIN_VERSION,
                text.getText()), ownerPub);
            if (blob.length() <= limit)
            {
                return new Result(blob, text.getVisits(), text.getShown());
            }
            if (text.getShown() == 0)
            {
                return null;
            }
            max = text.getShown() - 1;
        }
    }

    /** Seals with a random ephemeral key and nonce. */
    static String seal(Gson plain, Envelope envelope, byte[] ownerPub) throws GeneralSecurityException
    {
        return PREFIX + encode(SealedBox.seal(deflate(json(plain, envelope)), ownerPub, INFO));
    }

    /** Seals with a fixed ephemeral key and nonce, for the test vector. */
    static String seal(Gson plain, Envelope envelope, byte[] ownerPub, byte[] ephemeralPriv, byte[] nonce)
        throws GeneralSecurityException
    {
        return PREFIX + encode(SealedBox.seal(deflate(json(plain, envelope)), ownerPub, INFO, ephemeralPriv, nonce));
    }

    /** "Copied encrypted history (3 visits, 1,840 chars)". */
    public static String copiedMessage(Result result)
    {
        return String.format(Locale.US, "Copied encrypted history (%d %s, %,d chars)", result.getShown(),
            result.getShown() == 1 ? "visit" : "visits", result.getBlob().length());
    }

    private static byte[] json(Gson plain, Envelope envelope)
    {
        return plain.toJson(envelope).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] deflate(byte[] data)
    {
        Deflater deflater = new Deflater(9, true);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 64);
        byte[] buffer = new byte[4096];
        while (!deflater.finished())
        {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static String encode(byte[] bytes)
    {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
