package com.rfl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PaletteTest
{
	/**
	 * Expected values generated once from the pack tool's vendored
	 * {@code tools/replay-pack/vendor/xrsps/client/rs/util/ColorUtil.ts} {@code HSL_RGB_MAP}
	 * (buildPalette at brightness 0.8) with a throwaway tsx script, then hard-coded.
	 * HSL16 = hue(6) << 10 | saturation(3) << 7 | lightness(7).
	 */
	@Test
	public void matchesClientPalette()
	{
		// hue 0 (extreme), sat 7, light 64
		assertEquals(0xf91f0f, Palette.rgb(960));
		// hue 63 (extreme), sat 7, light 64
		assertEquals(0xf90f1f, Palette.rgb(65472));
		// saturation 0, hue 10, light 64
		assertEquals(0x9a998b, Palette.rgb(10304));
		// low lightness: hue 21, sat 4, light 5
		assertEquals(0x091a09, Palette.rgb(22021));
		// high lightness: hue 42, sat 5, light 120
		assertEquals(0xeaeafb, Palette.rgb(43768));
		// lightness 0 is black, which the client bumps to 1
		assertEquals(0x000001, Palette.rgb(0));
	}

	/**
	 * Whole-table checksum, h = h * 31 + rgb over HSL16 0..65535 (int overflow), computed from
	 * the same vendored HSL_RGB_MAP. Catches any single-entry drift (for example Math.pow).
	 */
	@Test
	public void wholeTableMatchesClientPalette()
	{
		int h = 0;
		for (int i = 0; i < 65536; i++)
		{
			h = h * 31 + Palette.rgb(i);
		}
		assertEquals(-190389674, h);
	}

	@Test
	public void tableEdgesMatchPackTool()
	{
		assertEquals(0xfff9f9, Palette.rgb(65534));
		// The client table has 0xffff entries; the pack falls back to black for 0xffff.
		assertEquals(0, Palette.rgb(65535));
		// Only the low 16 bits are the HSL (lit faceColors carry light in the high bits).
		assertEquals(0xf91f0f, Palette.rgb((5 << 16) | 960));
	}
}
