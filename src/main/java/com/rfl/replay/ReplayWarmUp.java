package com.rfl.replay;

import com.rfl.log.PluginEntry;
import com.rfl.replay.pitch.Loc;
import com.rfl.replay.pitch.LocModel;
import com.rfl.replay.pitch.PitchCapture;
import com.rfl.replay.pitch.PitchFloor;
import com.rfl.replay.pitch.PitchObjects;
import sh.yumekui.toolkit.model.ModelCapture;
import sh.yumekui.toolkit.model.Palette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs the replay code paths once on made-up data, off the client thread, so pressing Record
 * doesn't pay first-use costs on a frame: class loading and static tables ({@link Palette}'s
 * 64K-entry colour table), lambda linkage, and Gson's type adapters for every line shape. Pure:
 * it never touches the client, and nothing it builds is kept or written. The numbers below are
 * arbitrary sample values: any will do, as long as each code path runs once.
 */
@Slf4j
public final class ReplayWarmUp
{
    private ReplayWarmUp()
    {
    }

    /** Any thread but the client's. Never throws; false when a path failed (logged at debug). */
    public static boolean run(Gson gson)
    {
        try
        {
            final long start = System.nanoTime();
            final List<Object> lines = new ArrayList<>();
            Palette.rgb(0);

            final ReplaySampler sampler = new ReplaySampler();
            final int[] equipment = new int[12];
            final int[] colors = new int[5];
            final Integer look = Appearance.hash(0, equipment, colors);
            lines.addAll(sampler.tick(1, 1, List.of(new Appearance("warm", 0, equipment, colors)),
                List.of(new TrueTile("warm", 64, 64))));
            final PlayerState player = new PlayerState("warm", 64, 64, 0, -1, 0, 808, 0,
                new int[] { 1, 0, 92 }, ReplayWarmUp::geometry, look);
            final Ball ball = new Ball(1, 0, 1.0, 2.0, 3.0, 0, ReplayWarmUp::geometry);
            for (int cycle = 2; cycle < 6; cycle++)
            {
                lines.addAll(sampler.frame(cycle, List.of(player), List.of(ball)));
            }
            lines.addAll(sampler.frame(6, List.of(new PlayerState("warm", 64, 64, 0, -1, 0, 808, 1,
                PlayerState.NO_SPOTS, ReplayWarmUp::geometry, look)), List.of()));

            final List<Loc> locs = new ArrayList<>();
            for (int k = 0; k < 4; k++)
            {
                locs.add(Loc.withReasons(k, 10, 0, k == 0 ? 0 : 256, 64, 64, 0,
                    () -> LocModel.of(ModelCapture.rotateY(geometry(), 65535, 0))));
            }
            final List<Map<String, Object>> sink = new ArrayList<>();
            final PitchCapture capture = new PitchCapture(new PitchCapture.Sink()
            {
                @Override
                public void models(List<Map<String, Object>> models)
                {
                    sink.addAll(models);
                }

                @Override
                public void pitch(Map<String, Object> line)
                {
                    sink.add(line);
                }

                @Override
                public void locs(Map<String, Object> line)
                {
                    sink.add(line);
                }
            }, 0L, System::nanoTime);
            final Map<String, Object> pitch = new LinkedHashMap<>();
            pitch.put("t", "pitch");
            pitch.put("heights", new int[104][104]);
            pitch.put("chunksAll", new int[4][13][13]);
            pitch.put("objs", new PitchObjects().rows());
            pitch.put("under", PitchFloor.crop(new short[104][104], 50, 50, 20));
            pitch.put("locs", null);
            pitch.put("paint", PitchFloor.paint(new int[104][104], 50, 50, 20));
            capture.begin(sampler, pitch, locs);
            while (capture.pending())
            {
                capture.step();
            }
            lines.addAll(sink);
            lines.add(ReplayLines.plugins(1, List.of(new PluginEntry("warm", true, PluginEntry.BUILTIN))));
            lines.add(ReplayLines.pluginToggle(1, "warm", false));

            int chars = 0;
            for (final Object line : lines)
            {
                chars += gson.toJson(line).length();
            }
            log.debug("RFL replay warm-up: {} lines, {} chars, {} us", lines.size(), chars,
                (System.nanoTime() - start) / 1000);
            return true;
        }
        catch (RuntimeException | LinkageError e)
        {
            log.debug("RFL replay warm-up failed", e);
            return false;
        }
    }

    /** A small client-like model through {@link ModelCapture#capture}. */
    private static ModelCapture.Geometry geometry()
    {
        final float[] v = { 0f, 1f, 2f };
        return ModelCapture.capture(v, v, v, 3, new int[] { 0 }, new int[] { 1 }, new int[] { 2 }, 1,
            new int[] { 1000 }, new int[] { 1000 }, null, null);
    }
}
