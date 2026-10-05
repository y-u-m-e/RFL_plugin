package sh.yumekui.toolkit.model;

/*
 * Original implementation, written from these public sources only:
 *
 *  1. The packed 16-bit OSRS colour layout, as documented by RuneLite's BSD-2-Clause
 *     net.runelite.api.JagexColor (runelite-api 1.13.1): hue in bits 10-15 (HUE_MAX 63),
 *     saturation in bits 7-9 (SATURATION_MAX 7), luminance in bits 0-6 (LUMINANCE_MAX 127),
 *     as packed by packHSL and read back by unpackHue / unpackSaturation / unpackLuminance.
 *  2. The standard HSL-to-RGB conversion (chroma, hue sector, intermediate value), as given in
 *     textbooks and at https://en.wikipedia.org/wiki/HSL_and_HSV#HSL_to_RGB.
 *  3. The brightness gamma as the inverse of JagexColor.rgbToHSL(int rgb, double brightness),
 *     which raises each channel c / 256 to the power 1 / brightness before converting. Here
 *     each channel x in [0, 1] becomes 256 * x^brightness, clamped to 255. rgbToHSL also maps
 *     rgb 1 to HSL 0, so black is emitted as 1.
 */

/**
 * The client's 16-bit HSL face colours as 0xRRGGBB, from a 65536-entry table built once on
 * first use. Pure and thread-safe after class initialisation.
 */
public final class Palette
{
    /** The brightness the table is built at, the same one the plugin has always used. */
    static final double BRIGHTNESS = 0.8;

    private static final int HUE_SHIFT = 10;
    private static final int SATURATION_SHIFT = 7;
    private static final int HUE_MASK = 63;
    private static final int SATURATION_MASK = 7;
    private static final int LUMINANCE_MASK = 127;
    /** Hue levels around the circle (HUE_MAX + 1), and the HSL maxima (JagexColor). */
    private static final double HUE_LEVELS = 64.0;
    private static final double SATURATION_MAX = 7.0;
    private static final double LUMINANCE_MAX = 127.0;
    private static final int ENTRIES = 1 << 16;
    private static final int LOW_16 = 0xffff;
    private static final double CHANNEL_SCALE = 256.0;
    private static final int CHANNEL_MAX = 255;
    /** JagexColor.rgbToHSL maps rgb 1 to HSL 0: the client's black. */
    private static final int CLIENT_BLACK = 1;

    private static int builds;
    private static final int[] TABLE = build(BRIGHTNESS);

    private Palette()
    {
    }

    /**
     * 0xRRGGBB for a client face colour. Only the low 16 bits are the HSL; lit face colours carry
     * other data above them, which is ignored.
     */
    public static int rgb(int hsl16)
    {
        return TABLE[hsl16 & LOW_16];
    }

    /** Tests: how many times the table has been built (once per class load). */
    static int builds()
    {
        return builds;
    }

    /** Tests: one entry computed directly, without the table. */
    static int compute(int hsl16, double brightness)
    {
        double hue = ((hsl16 >> HUE_SHIFT) & HUE_MASK) / HUE_LEVELS;
        double saturation = ((hsl16 >> SATURATION_SHIFT) & SATURATION_MASK) / SATURATION_MAX;
        double luminance = (hsl16 & LUMINANCE_MASK) / LUMINANCE_MAX;

        // Standard HSL to RGB, every component in [0, 1] and hue as a fraction of the circle.
        double chroma = (1.0 - Math.abs(2.0 * luminance - 1.0)) * saturation;
        double sector = hue * 6.0;
        double x = chroma * (1.0 - Math.abs(sector % 2.0 - 1.0));
        double r;
        double g;
        double b;
        if (sector < 1.0)
        {
            r = chroma;
            g = x;
            b = 0.0;
        }
        else if (sector < 2.0)
        {
            r = x;
            g = chroma;
            b = 0.0;
        }
        else if (sector < 3.0)
        {
            r = 0.0;
            g = chroma;
            b = x;
        }
        else if (sector < 4.0)
        {
            r = 0.0;
            g = x;
            b = chroma;
        }
        else if (sector < 5.0)
        {
            r = x;
            g = 0.0;
            b = chroma;
        }
        else
        {
            r = chroma;
            g = 0.0;
            b = x;
        }
        double m = luminance - chroma / 2.0;

        int rgb = channel(r + m, brightness) << 16 | channel(g + m, brightness) << 8 | channel(b + m, brightness);
        return rgb == 0 ? CLIENT_BLACK : rgb;
    }

    /** One channel in [0, 1] through the brightness gamma, as 0..255. */
    private static int channel(double linear, double brightness)
    {
        double clamped = Math.max(0.0, Math.min(1.0, linear));
        return Math.min(CHANNEL_MAX, (int) (CHANNEL_SCALE * Math.pow(clamped, brightness)));
    }

    private static int[] build(double brightness)
    {
        builds++;
        int[] table = new int[ENTRIES];
        for (int i = 0; i < ENTRIES; i++)
        {
            table[i] = compute(i, brightness);
        }
        return table;
    }
}
