package com.rfl.contact;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Finds the faces of a held ball inside a player's drawn model, so the holder's hitbox can leave
 * them out: a collision is bodies touching, never the ball.
 *
 * <p>How the client builds a player model: each of the 12 equipment slots contributes its worn
 * model (or identity kit) in slot order, and the parts are merged by appending faces and vertices
 * as they are, each part's face indices shifted by the vertices before it. So the weapon's faces
 * are one contiguous block, and inside it the face indices minus the block's first vertex are the
 * worn model's own face indices. That shape is what is matched: the worn model is loaded from the
 * cache ({@code Client#loadModelData}) and its face index triples are looked for, as one block,
 * in the player's model. Recorded replays confirm it: a held handegg always added exactly 224
 * faces over 114 contiguous vertices, with the same relative indices in every outfit.
 *
 * <p>Only an exact, unique match is used. No match, or more than one, leaves out nothing, so a
 * body face is never dropped on a guess; the ball then still counts, as before.
 *
 * <p>Face indices never change with the pose, so a result is kept per player until their gender
 * or equipment changes: a frame with the same appearance costs one array compare. Client thread only.
 */
public final class HeldBallFaces
{
    /** Leaves out nothing. */
    public static final int[] NONE = {0, 0};

    /** A model's face index triples. */
    public static final class Shape
    {
        final int[] a;
        final int[] b;
        final int[] c;
        final int faces;

        public Shape(int[] a, int[] b, int[] c, int faces)
        {
            this.a = a;
            this.b = b;
            this.c = c;
            this.faces = faces;
        }
    }

    /** Loads a worn model's {@link Shape} by model id; null while it can't be loaded. */
    public interface Models
    {
        Shape load(int modelId);
    }

    /** One player's last appearance and the face range found for it. */
    private static final class Entry
    {
        final int gender;
        final int[] equipment;
        final int[] range;

        Entry(int gender, int[] equipment, int[] range)
        {
            this.gender = gender;
            this.equipment = equipment;
            this.range = range;
        }
    }

    private final Models models;
    private final Map<Integer, Shape> shapes = new HashMap<>();
    private final Map<String, Entry> byPlayer = new HashMap<>();
    /** How many times a range was searched for; tests check it is once per appearance. */
    int searches;

    public HeldBallFaces(Models models)
    {
        this.models = models;
    }

    /**
     * The held ball's faces in this player's model as {start, end} (end exclusive), or
     * {@link #NONE}.
     *
     * @param name      sanitized player name, the cache key
     * @param gender    the composition's gender
     * @param equipment the composition's equipment ids; read, never kept (a copy is)
     * @param wornModel the held ball's worn model id, or -1 when they hold no ball
     * @param f1        the model's face index arrays and face count, any pose
     */
    public int[] range(String name, int gender, int[] equipment, int wornModel, int[] f1, int[] f2, int[] f3,
        int faceCount)
    {
        if (wornModel < 0 || equipment == null)
        {
            return NONE;
        }
        Entry entry = byPlayer.get(name);
        if (entry == null || entry.gender != gender || !Arrays.equals(entry.equipment, equipment))
        {
            Shape shape = shape(wornModel);
            if (shape == null)
            {
                // Not loadable yet: nothing is cached, so a later frame tries again.
                return NONE;
            }
            searches++;
            int start = find(f1, f2, f3, faceCount, shape);
            entry = new Entry(gender, equipment.clone(), start < 0 ? NONE : new int[] {start, start + shape.faces});
            byPlayer.put(name, entry);
        }
        // A model with fewer faces than the cached block (not this appearance after all) leaves out nothing.
        return entry.range[1] <= faceCount ? entry.range : NONE;
    }

    private Shape shape(int modelId)
    {
        Shape shape = shapes.get(modelId);
        if (shape == null)
        {
            shape = models.load(modelId);
            if (shape != null)
            {
                shapes.put(modelId, shape);
            }
        }
        return shape;
    }

    /**
     * Where {@code shape} sits in the model as one block: the first face index of a start such that
     * every face of the block equals the shape's face shifted by one constant vertex offset. -1
     * when there is no such block, or more than one (ambiguous, so nothing is left out).
     */
    static int find(int[] f1, int[] f2, int[] f3, int faceCount, Shape shape)
    {
        int n = shape.faces;
        if (f1 == null || f2 == null || f3 == null || n == 0 || faceCount < n)
        {
            return -1;
        }
        int found = -1;
        for (int start = 0; start + n <= faceCount; start++)
        {
            if (matches(f1, f2, f3, start, shape))
            {
                if (found >= 0)
                {
                    return -1;
                }
                found = start;
            }
        }
        return found;
    }

    private static boolean matches(int[] f1, int[] f2, int[] f3, int start, Shape shape)
    {
        int offset = f1[start] - shape.a[0];
        for (int k = 0; k < shape.faces; k++)
        {
            int at = start + k;
            if (f1[at] - offset != shape.a[k] || f2[at] - offset != shape.b[k] || f3[at] - offset != shape.c[k])
            {
                return false;
            }
        }
        return true;
    }

    /** Forgets players no longer in view, once there are more cached than in view. */
    public void retain(Set<String> inView)
    {
        if (byPlayer.size() > inView.size())
        {
            byPlayer.keySet().retainAll(inView);
        }
    }

    /** Forgets every player and loaded model (leaving the house, a hop, shutdown). */
    public void reset()
    {
        byPlayer.clear();
        shapes.clear();
    }
}
