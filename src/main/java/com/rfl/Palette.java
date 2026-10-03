package com.rfl;

/**
 * The OSRS client colour palette: HSL16 to RGB at brightness 0.8, the client's default
 * (BRIGHTNESS_LOW). A straight port of xrsps {@code ColorUtil.buildPalette(0.8, 0, 512)}, the
 * table the replay pack tool colours faces with, so recorded models match packed ones exactly.
 * HSL16 is {@code hue(6) << 10 | saturation(3) << 7 | lightness(7)}.
 * Built once on class load and read-only afterwards, so it is safe from any thread.
 */
final class Palette
{
	private static final double BRIGHTNESS = 0.8;

	/** 0xffff entries, like the client; index 0xffff has no entry and maps to black. */
	private static final int[] TABLE = build(BRIGHTNESS);

	private Palette()
	{
	}

	/** RGB ({@code 0xRRGGBB}) for the low 16 bits of {@code hsl16}. */
	static int rgb(int hsl16)
	{
		int i = hsl16 & 0xffff;
		return i < TABLE.length ? TABLE[i] : 0;
	}

	private static int[] build(double brightness)
	{
		int[] palette = new int[0xffff];
		int index = 0;
		for (int hs = 0; hs < 512; hs++)
		{
			double hue = (hs >> 3) / 64.0 + 0.0078125;
			double sat = (hs & 7) / 8.0 + 0.0625;
			for (int l = 0; l < 128; l++)
			{
				double light = l / 128.0;
				double r = light;
				double g = light;
				double b = light;
				if (sat != 0.0)
				{
					double q = light < 0.5 ? light * (1.0 + sat) : light + sat - light * sat;
					double p = 2.0 * light - q;
					double hr = hue + 0.3333333333333333;
					if (hr > 1.0)
					{
						hr--;
					}
					double hb = hue - 0.3333333333333333;
					if (hb < 0.0)
					{
						hb++;
					}
					r = channel(p, q, hr);
					g = channel(p, q, hue);
					b = channel(p, q, hb);
				}
				int rgb = ((int) (r * 256.0) << 16) + ((int) (g * 256.0) << 8) + (int) (b * 256.0);
				rgb = brighten(rgb, brightness);
				if (rgb == 0)
				{
					rgb = 1;
				}
				if (index < palette.length)
				{
					palette[index] = rgb;
				}
				index++;
			}
		}
		return palette;
	}

	private static double channel(double p, double q, double t)
	{
		if (6.0 * t < 1.0)
		{
			return p + (q - p) * 6.0 * t;
		}
		if (2.0 * t < 1.0)
		{
			return q;
		}
		if (3.0 * t < 2.0)
		{
			return p + (q - p) * (0.6666666666666666 - t) * 6.0;
		}
		return p;
	}

	private static int brighten(int rgb, double brightness)
	{
		double r = Math.pow((rgb >> 16) / 256.0, brightness);
		double g = Math.pow(((rgb >> 8) & 255) / 256.0, brightness);
		double b = Math.pow((rgb & 255) / 256.0, brightness);
		return ((int) (r * 256.0) << 16) | ((int) (g * 256.0) << 8) | (int) (b * 256.0);
	}
}
