package com.rfl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Turns a client model's arrays into compact replay geometry, and keeps one id per model key.
 * Pure: it never touches the client. The caller passes the {@code net.runelite.api.Model}
 * getters ({@code getVerticesX/Y/Z}, {@code getVerticesCount}, {@code getFaceIndices1/2/3},
 * {@code getFaceCount}, {@code getFaceColors1/3}, {@code getFaceTransparencies},
 * {@code getFaceTextures}). Mirrors the replay pack tool's {@code modelToJson}: hidden faces
 * (transparency 255, or colour3 == -2) are dropped, textured faces are neutral grey, and every
 * other face is {@code faceColors1} through {@link Palette}. Vertices stay in raw client
 * orientation (the viewer rotates), rounded to ints.
 */
final class ModelCapture
{
	/** Neutral grey for textured faces, matching the pack tool (no textures drawn). */
	static final int TEXTURED_GREY = 128;

	private ModelCapture()
	{
	}

	/** Captured geometry. The arrays are owned copies; treat them as immutable. */
	static final class Geometry
	{
		/** x, y, z per vertex, model units, raw client orientation. */
		final int[] vertices;
		/** Three vertex indices per kept face. */
		final int[] faces;
		/** r, g, b (0..255) per kept face. */
		final int[] colors;

		Geometry(int[] vertices, int[] faces, int[] colors)
		{
			this.vertices = vertices;
			this.faces = faces;
			this.colors = colors;
		}

		int vertexCount()
		{
			return vertices.length / 3;
		}

		int faceCount()
		{
			return faces.length / 3;
		}
	}

	/** A geometry captured under a new key, with the id it was given. */
	static final class Captured
	{
		final int id;
		final Geometry geometry;

		Captured(int id, Geometry geometry)
		{
			this.id = id;
			this.geometry = geometry;
		}
	}

	/** Same as the full overload, with no face textures. */
	static Geometry capture(float[] vx, float[] vy, float[] vz, int vCount,
		int[] f1, int[] f2, int[] f3, int fCount,
		int[] colors1, int[] colors3, byte[] transparencies)
	{
		return capture(vx, vy, vz, vCount, f1, f2, f3, fCount, colors1, colors3, transparencies, null);
	}

	/**
	 * Copies the model's arrays into a new {@link Geometry}. {@code transparencies} and
	 * {@code textures} may be null (the client leaves them null on models without them).
	 * Only the first {@code vCount} vertices and {@code fCount} faces are read.
	 */
	static Geometry capture(float[] vx, float[] vy, float[] vz, int vCount,
		int[] f1, int[] f2, int[] f3, int fCount,
		int[] colors1, int[] colors3, byte[] transparencies, short[] textures)
	{
		int[] vertices = new int[vCount * 3];
		for (int v = 0; v < vCount; v++)
		{
			vertices[v * 3] = Math.round(vx[v]);
			vertices[v * 3 + 1] = Math.round(vy[v]);
			vertices[v * 3 + 2] = Math.round(vz[v]);
		}

		int kept = 0;
		for (int f = 0; f < fCount; f++)
		{
			if (!hidden(f, colors3, transparencies))
			{
				kept++;
			}
		}

		int[] faces = new int[kept * 3];
		int[] colors = new int[kept * 3];
		int k = 0;
		for (int f = 0; f < fCount; f++)
		{
			if (hidden(f, colors3, transparencies))
			{
				continue;
			}
			faces[k] = f1[f];
			faces[k + 1] = f2[f];
			faces[k + 2] = f3[f];
			if (textures != null && textures[f] != -1)
			{
				colors[k] = TEXTURED_GREY;
				colors[k + 1] = TEXTURED_GREY;
				colors[k + 2] = TEXTURED_GREY;
			}
			else
			{
				int rgb = Palette.rgb(colors1[f]);
				colors[k] = (rgb >> 16) & 0xff;
				colors[k + 1] = (rgb >> 8) & 0xff;
				colors[k + 2] = rgb & 0xff;
			}
			k += 3;
		}
		return new Geometry(vertices, faces, colors);
	}

	private static boolean hidden(int f, int[] colors3, byte[] transparencies)
	{
		int alpha = transparencies != null ? transparencies[f] & 0xff : 0;
		return alpha == 255 || colors3[f] == -2;
	}

	/**
	 * Assigns ids 0, 1, 2, ... to model keys, capturing each key's geometry only the first time
	 * it is seen. Later models for the same key (for example animation-smoothing tweens) are
	 * ignored, so a recording never grows with variants.
	 *
	 * <p>Not thread-safe, by design: use it from the client thread only. {@link #takeNew()} hands
	 * newly captured geometry over for serialisation on the writer's thread; the registry never
	 * touches a returned {@link Geometry} again.
	 */
	static final class Registry
	{
		private final Map<String, Integer> ids = new HashMap<>();
		private List<Captured> fresh = new ArrayList<>();

		/**
		 * The id for {@code key}. On a new key, calls {@code capture} once and queues its
		 * geometry for {@link #takeNew()}; on a known key, {@code capture} is not called. When
		 * {@code capture} yields null (no model this time), returns -1 and registers nothing, so a
		 * later call can still capture the key.
		 */
		int idFor(String key, Supplier<Geometry> capture)
		{
			Integer known = ids.get(key);
			if (known != null)
			{
				return known;
			}
			Geometry geometry = capture.get();
			if (geometry == null)
			{
				return -1;
			}
			int id = ids.size();
			ids.put(key, id);
			fresh.add(new Captured(id, geometry));
			return id;
		}

		/** Newly captured geometries since the last call, in id order. Each is returned once. */
		List<Captured> takeNew()
		{
			if (fresh.isEmpty())
			{
				return new ArrayList<>();
			}
			List<Captured> out = fresh;
			fresh = new ArrayList<>();
			return out;
		}

		/** Distinct keys seen so far (models captured). */
		int size()
		{
			return ids.size();
		}
	}
}
