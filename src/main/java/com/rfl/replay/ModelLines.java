package com.rfl.replay;

import sh.yumekui.toolkit.model.ModelCapture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The file's models (spec §2.3): one id per model key, the first model seen under a key kept, and
 * each new id written once as a {@code model} line before any line that refers to it.
 *
 * <p>A player pose whose appearance already has a base model with the same faces and colours is
 * written as {@code base} + {@code dv} (vertex deltas, same vertex order); anything else in full,
 * and the first full player model of an appearance becomes its base.
 *
 * <p>Client thread only; the geometry handed out in lines is never touched again.
 */
final class ModelLines
{
    /** {@code model.kind} values. */
    static final String PLAYER = "player";
    static final String BALL = "ball";
    static final String LOC = "loc";

    private final ModelCapture.Registry registry = new ModelCapture.Registry();
    /** Kind of each id captured but not yet turned into a model line. */
    private final Map<Integer, String> pendingKind = new LinkedHashMap<>();
    /** Appearance hash of each player id captured but not yet turned into a model line. */
    private final Map<Integer, Integer> pendingAppearance = new LinkedHashMap<>();
    /** First full player model per appearance hash: the base later poses are delta-encoded against. */
    private final Map<Integer, ModelCapture.Captured> appearanceBase = new LinkedHashMap<>();

    /**
     * The id for {@code key}, calling {@code capture} only when the key is new; -1 when the capture
     * yields no model (the key stays new, so a later call can try again). A null {@code capture}
     * only looks the key up.
     *
     * @param appearance the player's appearance hash, for delta encoding; ignored for other kinds
     */
    int idFor(String key, String kind, Supplier<ModelCapture.Geometry> capture, int appearance)
    {
        int before = registry.size();
        int id = registry.idFor(key, capture == null ? () -> null : capture);
        if (registry.size() != before)
        {
            pendingKind.put(id, kind);
            if (PLAYER.equals(kind))
            {
                pendingAppearance.put(id, appearance);
            }
        }
        return id;
    }

    /** Distinct model keys captured so far. */
    int size()
    {
        return registry.size();
    }

    /** {@code model} lines for every id captured since the last call, in id order. */
    List<Map<String, Object>> takeLines()
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModelCapture.Captured captured : registry.takeNew())
        {
            out.add(line(captured));
        }
        return out;
    }

    private Map<String, Object> line(ModelCapture.Captured captured)
    {
        String kind = pendingKind.remove(captured.id);
        Integer appearance = pendingAppearance.remove(captured.id);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", "model");
        line.put("id", captured.id);
        line.put("kind", kind);
        ModelCapture.Captured base = appearance == null ? null : appearanceBase.get(appearance);
        if (base != null && sameTopology(base.geometry, captured.geometry))
        {
            line.put("base", base.id);
            line.put("dv", deltas(base.geometry.vertices, captured.geometry.vertices));
        }
        else
        {
            if (appearance != null && base == null)
            {
                appearanceBase.put(appearance, captured);
            }
            line.put("v", captured.geometry.vertices);
            line.put("f", captured.geometry.faces);
            line.put("c", captured.geometry.colors);
        }
        return line;
    }

    /** Same vertex count, faces and colours: only the vertex positions differ. */
    private static boolean sameTopology(ModelCapture.Geometry base, ModelCapture.Geometry pose)
    {
        return base.vertices.length == pose.vertices.length && Arrays.equals(base.faces, pose.faces)
            && Arrays.equals(base.colors, pose.colors);
    }

    private static int[] deltas(int[] base, int[] vertices)
    {
        int[] deltas = new int[vertices.length];
        for (int at = 0; at < deltas.length; at++)
        {
            deltas[at] = vertices[at] - base[at];
        }
        return deltas;
    }
}
