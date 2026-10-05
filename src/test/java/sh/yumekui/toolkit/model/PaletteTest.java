package sh.yumekui.toolkit.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import net.runelite.api.JagexColor;
import org.junit.Test;

/**
 * {@link Palette} checked against RuneLite's own {@link JagexColor#rgbToHSL}, which it inverts:
 * colours survive the round trip to within one packed step, the extremes land where they should,
 * and the table is built once.
 */
public class PaletteTest
{
    private static final double B = Palette.BRIGHTNESS;

    private static short hsl(int rgb)
    {
        return JagexColor.rgbToHSL(rgb, B);
    }

    /**
     * Hue steps apart, around the circle. rgbToHSL takes its hue {@code % 63}, so the hues it
     * returns run 0..62 and 62 sits next to 0.
     */
    private static int hueSteps(short a, short b)
    {
        int d = Math.abs(JagexColor.unpackHue(a) - JagexColor.unpackHue(b));
        return Math.min(d, JagexColor.HUE_MAX - d);
    }

    /**
     * rgb -> HSL -> palette -> HSL lands on the same or an adjacent packed value. Saturation and
     * luminance always; hue once there is enough colour for 8-bit channels to fix it (near grey or
     * near black, the hue of a truncated channel triple is not determined).
     */
    @Test
    public void roundTripsThroughRuneLitesRgbToHsl()
    {
        int checked = 0;
        for (int r = 0; r < 256; r += 5)
        {
            for (int g = 0; g < 256; g += 5)
            {
                for (int b = 0; b < 256; b += 5)
                {
                    short before = hsl(r << 16 | g << 8 | b);
                    int lum = JagexColor.unpackLuminance(before);
                    if (lum < 3 || lum > JagexColor.LUMINANCE_MAX - 3)
                    {
                        continue;
                    }
                    short after = hsl(Palette.rgb(before & 0xffff));
                    String at = String.format("%02x%02x%02x %s -> %s", r, g, b, JagexColor.formatHSL(before),
                        JagexColor.formatHSL(after));
                    assertTrue(at, Math.abs(lum - JagexColor.unpackLuminance(after)) <= 1);
                    assertTrue(at, Math.abs(JagexColor.unpackSaturation(before)
                        - JagexColor.unpackSaturation(after)) <= 1);
                    if (JagexColor.unpackSaturation(before) >= 3 && lum >= 8 && lum <= 119)
                    {
                        assertTrue(at, hueSteps(before, after) <= 1);
                    }
                    checked++;
                }
            }
        }
        assertTrue("sampled " + checked, checked > 100_000);
    }

    @Test
    public void blackIsTheClientsBlackAtEveryHueAndSaturation()
    {
        for (int h = 0; h <= JagexColor.HUE_MAX; h++)
        {
            for (int s = 0; s <= JagexColor.SATURATION_MAX; s++)
            {
                assertEquals(1, Palette.rgb(JagexColor.packHSL(h, s, 0)));
            }
        }
        // rgbToHSL maps rgb 1 to HSL 0, so black round-trips exactly.
        assertEquals(0, hsl(Palette.rgb(0)));
    }

    @Test
    public void whiteIsWhite()
    {
        assertEquals(0xffffff, Palette.rgb(JagexColor.packHSL(0, 0, JagexColor.LUMINANCE_MAX)));
        assertEquals(0xffffff, Palette.rgb(JagexColor.packHSL(40, 7, JagexColor.LUMINANCE_MAX)));
        int grey = Palette.rgb(JagexColor.packHSL(17, 0, 64));
        assertEquals("grey has equal channels", grey >> 16, grey & 0xff);
        assertEquals("grey has equal channels", grey >> 16, (grey >> 8) & 0xff);
    }

    @Test
    public void fullySaturatedPrimariesKeepTheirChannel()
    {
        int[] primaries = { 0xff0000, 0x00ff00, 0x0000ff, 0xffff00, 0x00ffff, 0xff00ff };
        for (int primary : primaries)
        {
            short packed = hsl(primary);
            assertEquals(JagexColor.SATURATION_MAX, JagexColor.unpackSaturation(packed));
            int rgb = Palette.rgb(packed & 0xffff);
            for (int shift = 0; shift <= 16; shift += 8)
            {
                int want = (primary >> shift) & 0xff;
                int got = (rgb >> shift) & 0xff;
                String at = Integer.toHexString(primary) + " -> " + Integer.toHexString(rgb);
                assertTrue(at, want == 0xff ? got >= 0xf0 : got <= 0x20);
            }
            short back = hsl(rgb);
            assertTrue(JagexColor.formatHSL(packed) + " -> " + JagexColor.formatHSL(back),
                hueSteps(packed, back) <= 1
                    && JagexColor.unpackSaturation(back) == JagexColor.SATURATION_MAX
                    && Math.abs(JagexColor.unpackLuminance(packed) - JagexColor.unpackLuminance(back)) <= 1);
        }
    }

    @Test
    public void tableIsBuiltOnceAndMatchesDirectComputation()
    {
        for (int i = 0; i < 65536; i += 97)
        {
            assertEquals(Palette.compute(i, B), Palette.rgb(i));
        }
        Palette.rgb(123);
        assertEquals(1, Palette.builds());
    }

    @Test
    public void onlyTheLow16BitsAreTheColour()
    {
        assertEquals(Palette.rgb(960), Palette.rgb((5 << 16) | 960));
        assertEquals(Palette.rgb(65535), Palette.rgb(-1));
    }
}
