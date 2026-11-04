package sh.yumekui.toolkit.crypto;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals bytes to a recipient's X25519 public key so only the holder of the matching private key
 * can read them, and any change to the sealed bytes makes opening fail. An anonymous sender: a
 * fresh ephemeral key pair per message, no sender key.
 *
 * <p>Layout: {@code ephemeralPub(32) | nonce(12) | AES-256-GCM ciphertext | tag(16)}. The AES key
 * is HKDF-SHA256 (RFC 5869) with ikm = X25519(ephemeralPriv, recipientPub), salt =
 * ephemeralPub ‖ recipientPub, the caller's {@code info}, length 32. Keys are raw 32-byte
 * RFC 7748 values (little-endian u-coordinate for public keys).
 *
 * <p>Only the JDK (11+): "XDH" key agreement, "AES/GCM/NoPadding", "HmacSHA256". No IO, any thread.
 * This is tamper-evidence against anyone without their own encoder, not proof of who sealed it:
 * anyone with the public key can seal any bytes they like.
 */
public final class SealedBox
{
    /** Raw X25519 key length. */
    public static final int KEY_BYTES = 32;
    /** AES-GCM nonce length. */
    public static final int NONCE_BYTES = 12;
    /** AES-GCM tag length. */
    public static final int TAG_BYTES = 16;

    private static final NamedParameterSpec CURVE = NamedParameterSpec.X25519;
    private static final byte[] BASE_POINT = basePoint();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SealedBox()
    {
    }

    /**
     * Seals {@code plain} with a random ephemeral key and nonce.
     *
     * @param recipientPub the recipient's raw 32-byte X25519 public key
     * @param info the HKDF info, which binds the message to its format
     * @throws GeneralSecurityException when the key is unusable (for example a low-order point)
     */
    public static byte[] seal(byte[] plain, byte[] recipientPub, byte[] info) throws GeneralSecurityException
    {
        byte[] ephemeralPriv = new byte[KEY_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(ephemeralPriv);
        RANDOM.nextBytes(nonce);
        return seal(plain, recipientPub, info, ephemeralPriv, nonce);
    }

    /**
     * Seals {@code plain} with the given ephemeral private key and nonce. For test vectors only: a
     * reused ephemeral key or nonce breaks the encryption.
     */
    public static byte[] seal(byte[] plain, byte[] recipientPub, byte[] info, byte[] ephemeralPriv, byte[] nonce)
        throws GeneralSecurityException
    {
        if (recipientPub.length != KEY_BYTES || ephemeralPriv.length != KEY_BYTES || nonce.length != NONCE_BYTES)
        {
            throw new GeneralSecurityException("bad key or nonce length");
        }
        byte[] ephemeralPub = publicKey(ephemeralPriv);
        byte[] key = key(x25519(ephemeralPriv, recipientPub), ephemeralPub, recipientPub, info);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BYTES * 8, nonce));
        byte[] sealed = aes.doFinal(plain);
        return ByteBuffer.allocate(KEY_BYTES + NONCE_BYTES + sealed.length)
            .put(ephemeralPub).put(nonce).put(sealed).array();
    }

    /**
     * Opens what {@link #seal} made.
     *
     * @throws GeneralSecurityException ({@link javax.crypto.AEADBadTagException}) when any byte was changed
     */
    public static byte[] open(byte[] sealed, byte[] recipientPriv, byte[] info) throws GeneralSecurityException
    {
        if (sealed.length < KEY_BYTES + NONCE_BYTES + TAG_BYTES)
        {
            throw new GeneralSecurityException("too short");
        }
        byte[] ephemeralPub = Arrays.copyOfRange(sealed, 0, KEY_BYTES);
        byte[] nonce = Arrays.copyOfRange(sealed, KEY_BYTES, KEY_BYTES + NONCE_BYTES);
        byte[] key = key(x25519(recipientPriv, ephemeralPub), ephemeralPub, publicKey(recipientPriv), info);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BYTES * 8, nonce));
        return aes.doFinal(sealed, KEY_BYTES + NONCE_BYTES, sealed.length - KEY_BYTES - NONCE_BYTES);
    }

    /** The raw public key for a raw private key: X25519(priv, 9). */
    public static byte[] publicKey(byte[] priv) throws GeneralSecurityException
    {
        return x25519(priv, BASE_POINT);
    }

    /** HKDF-SHA256 of the shared secret with salt = ephemeralPub ‖ recipientPub; 32 bytes. */
    static byte[] key(byte[] shared, byte[] ephemeralPub, byte[] recipientPub, byte[] info)
        throws GeneralSecurityException
    {
        byte[] salt = ByteBuffer.allocate(KEY_BYTES * 2).put(ephemeralPub).put(recipientPub).array();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(shared);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        // One expand block: SHA-256 gives exactly the 32 bytes needed.
        return mac.doFinal(new byte[] { 1 });
    }

    /** X25519(scalar, u), both raw 32-byte RFC 7748 values; the result is the raw shared u. */
    static byte[] x25519(byte[] scalar, byte[] u) throws GeneralSecurityException
    {
        KeyFactory factory = KeyFactory.getInstance("XDH");
        PrivateKey priv = factory.generatePrivate(new XECPrivateKeySpec(CURVE, scalar.clone()));
        PublicKey pub = factory.generatePublic(new XECPublicKeySpec(CURVE, uCoordinate(u)));
        KeyAgreement agreement = KeyAgreement.getInstance("XDH");
        agreement.init(priv);
        agreement.doPhase(pub, true);
        return agreement.generateSecret();
    }

    /** RFC 7748 decodeUCoordinate: little-endian, top bit masked. The JDK wants a BigInteger. */
    private static BigInteger uCoordinate(byte[] littleEndian)
    {
        byte[] big = new byte[littleEndian.length];
        for (int i = 0; i < big.length; i++)
        {
            big[i] = littleEndian[littleEndian.length - 1 - i];
        }
        big[0] &= 0x7f;
        return new BigInteger(1, big);
    }

    private static byte[] basePoint()
    {
        byte[] base = new byte[KEY_BYTES];
        base[0] = 9;
        return base;
    }
}
