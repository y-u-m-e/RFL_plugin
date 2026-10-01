package com.rfl;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Debug only: saves the local player's posed model vertices and the parts computed from them to
 * {@code ~/.runelite/rfl-debug/}, at most every few seconds, so hitbox fitting can be checked
 * against real models offline. The copy is taken on the client thread; the write happens off it.
 */
@Slf4j
@Singleton
final class ModelDumper
{
    private static final long INTERVAL_MS = 3000;

    private final ScheduledExecutorService executor;
    private final Gson gson;
    private long lastDumpAt;

    @Inject
    ModelDumper(ScheduledExecutorService executor, Gson gson)
    {
        this.executor = executor;
        this.gson = gson;
    }

    /** Call on the client thread with the vertices the body was built from. */
    void maybeDump(int tick, String rsn, boolean bare, int orientation, int animation, int pose,
        float[] xs, float[] ys, float[] zs, int count, int baseX, int baseY, Body body)
    {
        long now = System.currentTimeMillis();
        if (now - lastDumpAt < INTERVAL_MS)
        {
            return;
        }
        lastDumpAt = now;

        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("tick", tick);
        dump.put("rsn", rsn);
        dump.put("source", bare ? "bare" : "equipped");
        dump.put("orientation", orientation);
        dump.put("animation", animation);
        dump.put("poseAnimation", pose);
        dump.put("baseX", baseX);
        dump.put("baseY", baseY);
        dump.put("xs", java.util.Arrays.copyOf(xs, count));
        dump.put("ys", java.util.Arrays.copyOf(ys, count));
        dump.put("zs", java.util.Arrays.copyOf(zs, count));
        List<Map<String, Object>> parts = new ArrayList<>();
        for (Capsule c : body.parts)
        {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", c.name);
            p.put("a", new double[]{c.ax, c.ay, c.az});
            p.put("b", new double[]{c.bx, c.by, c.bz});
            p.put("radius", c.radius);
            parts.add(p);
        }
        dump.put("parts", parts);

        String json = gson.toJson(dump);
        String file = "model-" + tick + "-" + (bare ? "bare" : "equipped") + ".json";
        executor.execute(() ->
        {
            try
            {
                Path dir = RuneLite.RUNELITE_DIR.toPath().resolve("rfl-debug");
                Files.createDirectories(dir);
                Files.write(dir.resolve(file), json.getBytes(StandardCharsets.UTF_8));
                log.info("[RFL debug] dumped {}", dir.resolve(file));
            }
            catch (IOException e)
            {
                log.warn("[RFL debug] model dump failed", e);
            }
        });
    }
}
