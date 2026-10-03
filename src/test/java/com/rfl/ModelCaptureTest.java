package com.rfl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ModelCaptureTest
{
	// Four vertices, three faces.
	private static float[] vx() { return new float[]{0f, 10.4f, -3.6f, 7f}; }
	private static float[] vy() { return new float[]{1f, 2f, 3f, -4.5f}; }
	private static float[] vz() { return new float[]{5f, 6f, 7f, 8f}; }
	private static int[] f1() { return new int[]{0, 1, 2}; }
	private static int[] f2() { return new int[]{1, 2, 3}; }
	private static int[] f3() { return new int[]{2, 3, 0}; }

	private static final int RED = 960;       // 0xf91f0f
	private static final int BLUE = 43768;    // 0xeaeafb
	private static final int GREY = 10304;    // 0x9a998b

	private static ModelCapture.Geometry sample()
	{
		return ModelCapture.capture(vx(), vy(), vz(), 4, f1(), f2(), f3(), 3,
			new int[]{RED, BLUE, GREY}, new int[]{0, 0, 0}, null);
	}

	@Test
	public void keepsVerticesRawAndRounded()
	{
		ModelCapture.Geometry g = sample();
		// Raw client orientation, no y/z flip; floats rounded to ints.
		assertArrayEquals(new int[]{0, 1, 5, 10, 2, 6, -4, 3, 7, 7, -4, 8}, g.vertices);
		assertArrayEquals(new int[]{0, 1, 2, 1, 2, 3, 2, 3, 0}, g.faces);
		assertArrayEquals(new int[]{0xf9, 0x1f, 0x0f, 0xea, 0xea, 0xfb, 0x9a, 0x99, 0x8b}, g.colors);
		assertEquals(4, g.vertexCount());
		assertEquals(3, g.faceCount());
	}

	@Test
	public void dropsHiddenFaces()
	{
		// Face 0: transparency 255 (byte -1) -> hidden. Face 1: colour3 == -2 -> hidden.
		// Face 2: transparency 254 -> drawn.
		ModelCapture.Geometry g = ModelCapture.capture(vx(), vy(), vz(), 4, f1(), f2(), f3(), 3,
			new int[]{RED, BLUE, GREY}, new int[]{0, -2, 0}, new byte[]{(byte) 255, 0, (byte) 254});
		assertArrayEquals(new int[]{2, 3, 0}, g.faces);
		assertArrayEquals(new int[]{0x9a, 0x99, 0x8b}, g.colors);
		// Vertices are kept whole so face indices stay valid.
		assertEquals(4, g.vertexCount());
	}

	@Test
	public void texturedFacesAreGrey()
	{
		ModelCapture.Geometry g = ModelCapture.capture(vx(), vy(), vz(), 4, f1(), f2(), f3(), 3,
			new int[]{RED, BLUE, GREY}, new int[]{0, 0, 0}, null, new short[]{-1, 12, -1});
		assertArrayEquals(new int[]{0xf9, 0x1f, 0x0f, 128, 128, 128, 0x9a, 0x99, 0x8b}, g.colors);
	}

	@Test
	public void usesOnlyCountsNotArrayLengths()
	{
		// Client arrays can be longer than the live counts.
		ModelCapture.Geometry g = ModelCapture.capture(vx(), vy(), vz(), 2, f1(), f2(), f3(), 1,
			new int[]{RED, BLUE, GREY}, new int[]{0, 0, 0}, new byte[]{0, 0, 0});
		assertEquals(2, g.vertexCount());
		assertEquals(1, g.faceCount());
	}

	@Test
	public void copiesArrays()
	{
		float[] x = vx();
		float[] y = vy();
		float[] z = vz();
		int[] a = f1();
		int[] b = f2();
		int[] c = f3();
		int[] col1 = {RED, BLUE, GREY};
		int[] col3 = {0, 0, 0};
		byte[] tr = {0, 0, 0};
		ModelCapture.Geometry g = ModelCapture.capture(x, y, z, 4, a, b, c, 3, col1, col3, tr);
		int[] v0 = g.vertices.clone();
		int[] f0 = g.faces.clone();
		int[] c0 = g.colors.clone();

		x[0] = 999f;
		y[1] = 999f;
		z[2] = 999f;
		a[0] = 3;
		b[1] = 0;
		c[2] = 1;
		col1[0] = BLUE;
		col3[1] = -2;
		tr[2] = (byte) 255;

		assertArrayEquals(v0, g.vertices);
		assertArrayEquals(f0, g.faces);
		assertArrayEquals(c0, g.colors);
	}

	@Test
	public void keyedCaptureKeepsFirstModel()
	{
		ModelCapture.Registry reg = new ModelCapture.Registry();
		AtomicInteger calls = new AtomicInteger();
		ModelCapture.Geometry first = sample();
		int id = reg.idFor("p:1", () -> { calls.incrementAndGet(); return first; });
		// A tweened frame for the same key (animation smoothing) must not be captured.
		int again = reg.idFor("p:1", () -> { calls.incrementAndGet(); return sample(); });
		int other = reg.idFor("p:2", () -> { calls.incrementAndGet(); return sample(); });

		assertEquals(0, id);
		assertEquals(0, again);
		assertEquals(1, other);
		assertEquals(2, calls.get());
		assertEquals(2, reg.size());
		List<ModelCapture.Captured> fresh = reg.takeNew();
		assertSame(first, fresh.get(0).geometry);
	}

	@Test
	public void takeNewDrainsOnce()
	{
		ModelCapture.Registry reg = new ModelCapture.Registry();
		reg.idFor("a", ModelCaptureTest::sample);
		reg.idFor("b", ModelCaptureTest::sample);
		reg.idFor("a", ModelCaptureTest::sample);

		List<ModelCapture.Captured> first = reg.takeNew();
		assertEquals(2, first.size());
		assertEquals(0, first.get(0).id);
		assertEquals(1, first.get(1).id);
		assertTrue(reg.takeNew().isEmpty());

		reg.idFor("c", ModelCaptureTest::sample);
		List<ModelCapture.Captured> second = reg.takeNew();
		assertEquals(1, second.size());
		assertEquals(2, second.get(0).id);
		assertTrue(reg.takeNew().isEmpty());
	}

	@Test
	public void nullCaptureIsNotRegistered()
	{
		// No model this time (null renderable): no id, and the key stays open for a later capture.
		ModelCapture.Registry reg = new ModelCapture.Registry();
		assertEquals(-1, reg.idFor("l:1", () -> null));
		assertEquals(0, reg.size());
		assertTrue(reg.takeNew().isEmpty());
		assertEquals(0, reg.idFor("l:1", ModelCaptureTest::sample));
	}

	@Test
	public void rotateYTurnsLikeTheGpuPlugin()
	{
		ModelCapture.Geometry g = new ModelCapture.Geometry(new int[] { 100, -5, 0, 0, 7, 200 },
			new int[] { 0, 1, 1 }, new int[] { 1, 2, 3 });
		// Orientation 512 (a quarter turn): SINE = 65536, COSINE = 0, so x' = z and z' = -x.
		ModelCapture.Geometry turned = ModelCapture.rotateY(g, 65536, 0);
		assertArrayEquals(new int[] { 0, -5, -100, 200, 7, 0 }, turned.vertices);
		assertSame(g.faces, turned.faces);
		assertSame(g.colors, turned.colors);
		// The tables the recorder passes are a quarter turn at 512 (16.16, so just under 65536).
		assertEquals(65535, net.runelite.api.Perspective.SINE[512]);
		assertEquals(0, net.runelite.api.Perspective.COSINE[512]);
	}
}
