package com.rfl.replay;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * What the replay buffer costs in a crowded house: 20 players all moving every ClientTick and
 * changing pose often, for the default 3-minute clip plus the margin. Prints the numbers (the
 * README quotes them) and checks they stay under the cap.
 */
public class ReplayBufferMemoryTest
{
    private static final int PLAYERS = 20;
    private static final int SECONDS = 180 + ReplayRecorder.BUFFER_MARGIN_SECONDS;
    /** A player pose's vertex count (a geared player model is 1,000 to 2,000 vertices). */
    private static final int VERTICES = 1500;
    private static final int MODELS = 400;

    @Test
    public void twentyPlayersForThreeMinutesStayWellUnderTheCap() throws IOException
    {
        Gson gson = new GsonBuilder().create();
        ReplayRing ring = new ReplayRing(SECONDS, ReplayRing.DEFAULT_CAP_BYTES);
        ring.opened(null);
        Map<String, Object> hdr = ReplayLines.hdr(233, 330, "Ref", 0L, 0);
        ring.written(hdr, gson.toJson(hdr));
        Random random = new Random(7);
        for (int i = 0; i < PLAYERS; i++)
        {
            Map<String, Object> spawn = ReplayLines.spawn(0, i, "Player " + i);
            ring.written(spawn, gson.toJson(spawn));
        }
        int cycles = SECONDS * ReplayRing.CYCLES_PER_SECOND;
        long jsonChars = 0;
        for (int cyc = 1; cyc <= cycles; cyc++)
        {
            List<int[]> rows = new ArrayList<>();
            List<int[]> models = new ArrayList<>();
            for (int i = 0; i < PLAYERS; i++)
            {
                rows.add(new int[] { i, 6000 + random.nextInt(2000), 6000 + random.nextInt(2000),
                    random.nextInt(2048), 4230 + random.nextInt(50), random.nextInt(30), 808 + random.nextInt(20),
                    random.nextInt(30) });
                if (random.nextBoolean())
                {
                    models.add(new int[] { i, random.nextInt(5000) });
                }
            }
            jsonChars += feed(ring, gson, ReplayLines.poses(cyc, rows));
            jsonChars += feed(ring, gson, ReplayLines.playerModels(cyc, models));
            if (cyc % 30 == 0)
            {
                jsonChars += feed(ring, gson, ReplayLines.tick(cyc, cyc / 30));
                List<int[]> tiles = new ArrayList<>();
                for (int i = 0; i < PLAYERS; i++)
                {
                    tiles.add(new int[] { i, 6000 + random.nextInt(2000), 6000 + random.nextInt(2000) });
                }
                jsonChars += feed(ring, gson, ReplayLines.trueTiles(cyc, tiles));
            }
        }

        long modelRaw = 0;
        for (int id = 0; id < MODELS; id++)
        {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("t", "model");
            model.put("id", id);
            model.put("kind", "player");
            model.put("base", 0);
            int[] deltas = new int[VERTICES * 3];
            for (int k = 0; k < deltas.length; k++)
            {
                deltas[k] = random.nextInt(81) - 40;
            }
            model.put("dv", deltas);
            String json = gson.toJson(model);
            modelRaw += json.length();
            ring.written(model, json);
        }

        long started = System.nanoTime();
        ReplayRing.Snapshot snapshot = ring.snapshot();
        long snapshotMicros = (System.nanoTime() - started) / 1000;
        assertNotNull(snapshot);
        started = System.nanoTime();
        ClipBuilder.Plan plan = ClipBuilder.plan(snapshot, 180 * ReplayRing.CYCLES_PER_SECOND);
        long[] written = { 0 };
        ClipBuilder.write(gson, snapshot, plan, json -> written[0] += json.length() + 1);
        long buildMillis = (System.nanoTime() - started) / 1_000_000;

        System.out.printf("RFL replay buffer, %d players, %d s: ring %d lines, %.1f MB accounted (%.1f MB of JSON); "
                + "models %d deflated to %.1f MB from %.1f MB (%.1f KB each); snapshot %d us on the writer's thread; "
                + "clip cut and serialised in %d ms (%.1f MB raw)%n",
            PLAYERS, SECONDS, ring.ringSize(), ring.ringBytes() / 1048576.0, jsonChars / 1048576.0, ring.modelCount(),
            ring.modelBytes() / 1048576.0, modelRaw / 1048576.0, ring.modelBytes() / 1024.0 / MODELS, snapshotMicros,
            buildMillis, written[0] / 1048576.0);
        assertTrue("under the cap", ring.ringBytes() <= ReplayRing.DEFAULT_CAP_BYTES);
        assertTrue("the whole window fits: no cap trimming at 20 players", snapshot.cappedThrough == Integer.MIN_VALUE);
    }

    private static long feed(ReplayRing ring, Gson gson, Map<String, Object> line)
    {
        String json = gson.toJson(line);
        ring.written(line, json);
        return json.length();
    }
}
