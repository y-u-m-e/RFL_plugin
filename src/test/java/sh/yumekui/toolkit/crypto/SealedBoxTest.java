package sh.yumekui.toolkit.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Random;

import javax.crypto.AEADBadTagException;

import org.junit.Test;

/** {@link SealedBox}: round trip, tamper failure, and the RFC 7748 X25519 vector. */
public class SealedBoxTest
{
    private static final byte[] INFO = "test".getBytes(StandardCharsets.US_ASCII);

    private static byte[] hex(String s)
    {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** RFC 7748 section 6.1: Alice's public key from her private key (checks the little-endian u). */
    @Test
    public void publicKeyMatchesRfc7748() throws GeneralSecurityException
    {
        assertArrayEquals(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"),
            SealedBox.publicKey(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")));
    }

    @Test
    public void randomInputRoundTrips() throws GeneralSecurityException
    {
        Random random = new Random(7);
        byte[] priv = new byte[32];
        random.nextBytes(priv);
        byte[] pub = SealedBox.publicKey(priv);
        for (int size : new int[] { 0, 1, 31, 1000, 70_000 })
        {
            byte[] plain = new byte[size];
            random.nextBytes(plain);
            byte[] sealed = SealedBox.seal(plain, pub, INFO);
            assertEquals(32 + 12 + size + 16, sealed.length);
            assertArrayEquals(plain, SealedBox.open(sealed, priv, INFO));
        }
    }

    @Test
    public void flippingAnyByteFailsAuthentication() throws GeneralSecurityException
    {
        byte[] priv = new byte[32];
        new Random(3).nextBytes(priv);
        byte[] sealed = SealedBox.seal("hello".getBytes(StandardCharsets.UTF_8), SealedBox.publicKey(priv), INFO);
        for (int i = 0; i < sealed.length; i++)
        {
            byte[] bad = sealed.clone();
            bad[i] ^= 1;
            try
            {
                SealedBox.open(bad, priv, INFO);
                fail("byte " + i + " flipped and it still opened");
            }
            catch (GeneralSecurityException expected)
            {
                // the tag no longer matches (or the edited key is unusable)
            }
        }
    }

    @Test(expected = AEADBadTagException.class)
    public void anotherInfoFailsToOpen() throws GeneralSecurityException
    {
        byte[] priv = new byte[32];
        priv[0] = 5;
        byte[] sealed = SealedBox.seal(new byte[] { 1, 2, 3 }, SealedBox.publicKey(priv), INFO);
        SealedBox.open(sealed, priv, "other".getBytes(StandardCharsets.US_ASCII));
    }
}
