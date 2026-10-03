package com.rfl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Stress benchmark for the per-ClientTick detection and recording work at 10, 20 and 30 players.
 * Measures only; asserts nothing. Skipped unless the {@code rfl.benchmark} system property is
 * true, so {@code ./gradlew build} never runs it. Run it with {@code ./gradlew benchmark}
 * (optional: {@code -Pframes=1000 -Pwarmup=500}). Results print to stdout and to
 * {@code build/reports/benchmark/stress.md}.
 *
 * <p>Meshes: no real model fixture exists, so each player is a synthetic humanoid (head, torso,
 * two arms, two legs as closed ellipsoids, 1,920 triangles, about 200 x 80 x 28 local units) fed
 * through {@link PosedMesh#from} exactly as {@link ContactDetector} does with a posed model.
 *
 * <p>Movement: players run at 8 local units per frame on a 14 x 30 tile pitch, turning at random
 * and bouncing off the edges. A share of them are in pile-ups: groups of 3-4 jostling within
 * 25 units of a shared moving centre, so their meshes overlap and triangles cross every frame.
 * One player holds the handegg; with pile-ups, it is a pile-up member (the expensive case).
 *
 * <p>Per frame it times (a) building N meshes, (b) {@link ContactTracker#update} in display mode
 * (Show touching triangles on: same handegg-gated pairs as handegg-only, but a full count on every
 * one of them each frame) and handegg-only mode (display and debug off, sampled once per tick),
 * and (c) {@link ReplaySampler#frame}. A game tick passes every 30 frames, which forces a
 * full-count sample in handegg-only mode, as in game.
 */
public class StressBenchmarkTest
{
    private static final int[] PLAYERS = {10, 20, 30};
    private static final double[] PILE_UP_SHARES = {0.0, 0.1, 0.3};
    private static final int TILE = 128;
    private static final int PITCH_W = 14 * TILE;
    private static final int PITCH_H = 30 * TILE;
    private static final double SPEED = 8;
    private static final double JOSTLE = 25;
    private static final int FRAMES_PER_TICK = 30;

    private static int frames;
    private static int warmup;

    @BeforeClass
    public static void guard()
    {
        Assume.assumeTrue("benchmark: run with ./gradlew benchmark", Boolean.getBoolean("rfl.benchmark"));
        frames = Integer.getInteger("rfl.benchmark.frames", 1000);
        warmup = Integer.getInteger("rfl.benchmark.warmup", 500);
    }

    /** A synthetic bare-body model in client model space (y down, feet at y = 0). */
    static final class Body
    {
        final float[] xs;
        final float[] ys;
        final float[] zs;
        final int count;
        final int[] f1;
        final int[] f2;
        final int[] f3;
        final int faces;
        final byte[] transparencies;
        final int[] colors3;

        Body(List<float[]> verts, List<int[]> tris)
        {
            count = verts.size();
            xs = new float[count];
            ys = new float[count];
            zs = new float[count];
            for (int i = 0; i < count; i++)
            {
                xs[i] = verts.get(i)[0];
                ys[i] = verts.get(i)[1];
                zs[i] = verts.get(i)[2];
            }
            faces = tris.size();
            f1 = new int[faces];
            f2 = new int[faces];
            f3 = new int[faces];
            for (int i = 0; i < faces; i++)
            {
                f1[i] = tris.get(i)[0];
                f2[i] = tris.get(i)[1];
                f3[i] = tris.get(i)[2];
            }
            transparencies = new byte[faces];
            colors3 = new int[faces];
        }
    }

    /** Humanoid of six closed ellipsoids, 16 segments x 11 rings each: 6 x 320 = 1,920 triangles. */
    static Body humanoid()
    {
        List<float[]> verts = new ArrayList<>();
        List<int[]> tris = new ArrayList<>();
        // {centre x, height of centre, centre z, radius x, radius height, radius z}
        double[][] parts = {
            {0, 195, 0, 12, 15, 12},     // head
            {0, 135, 0, 24, 40, 14},     // torso
            {-32, 130, 0, 7, 40, 7},     // arms
            {32, 130, 0, 7, 40, 7},
            {-12, 50, 0, 10, 50, 10},    // legs
            {12, 50, 0, 10, 50, 10},
        };
        for (double[] p : parts)
        {
            ellipsoid(verts, tris, p, 16, 11);
        }
        return new Body(verts, tris);
    }

    private static void ellipsoid(List<float[]> verts, List<int[]> tris, double[] p, int seg, int rings)
    {
        int top = verts.size();
        verts.add(vertex(p, 0, 0));
        for (int r = 1; r < rings; r++)
        {
            double phi = Math.PI * r / rings;
            for (int s = 0; s < seg; s++)
            {
                verts.add(vertex(p, phi, 2 * Math.PI * s / seg));
            }
        }
        int bottom = verts.size();
        verts.add(vertex(p, Math.PI, 0));
        int first = top + 1;
        for (int s = 0; s < seg; s++)
        {
            int n = (s + 1) % seg;
            tris.add(new int[]{top, first + s, first + n});
            int last = first + (rings - 2) * seg;
            tris.add(new int[]{bottom, last + n, last + s});
        }
        for (int r = 0; r < rings - 2; r++)
        {
            int a = first + r * seg;
            int b = a + seg;
            for (int s = 0; s < seg; s++)
            {
                int n = (s + 1) % seg;
                tris.add(new int[]{a + s, b + s, b + n});
                tris.add(new int[]{a + s, b + n, a + n});
            }
        }
    }

    private static float[] vertex(double[] p, double phi, double theta)
    {
        double x = p[0] + p[3] * Math.sin(phi) * Math.cos(theta);
        double height = p[1] + p[4] * Math.cos(phi);
        double z = p[2] + p[5] * Math.sin(phi) * Math.sin(theta);
        // Client model space: y grows downwards, so height is negative y.
        return new float[]{(float) x, (float) -height, (float) z};
    }

    /** Moving players on the pitch; deterministic for a given seed. */
    static final class Pitch
    {
        final int n;
        final double[] x;
        final double[] y;
        final double[] heading;
        final int[] group;
        final double[] gx;
        final double[] gy;
        final double[] gHeading;
        final double[] ox;
        final double[] oy;
        final Random random;
        final String[] names;
        final String holder;

        Pitch(int n, double share, long seed)
        {
            this.n = n;
            random = new Random(seed);
            x = new double[n];
            y = new double[n];
            heading = new double[n];
            group = new int[n];
            ox = new double[n];
            oy = new double[n];
            names = new String[n];
            int piled = (int) Math.round(n * share);
            if (piled == 1)
            {
                piled = 2;
            }
            int groups = piled == 0 ? 0 : Math.max(1, (piled + 3) / 4);
            gx = new double[groups];
            gy = new double[groups];
            gHeading = new double[groups];
            for (int g = 0; g < groups; g++)
            {
                gx[g] = 200 + random.nextDouble() * (PITCH_W - 400);
                gy[g] = 200 + random.nextDouble() * (PITCH_H - 400);
                gHeading[g] = random.nextDouble() * 2 * Math.PI;
            }
            for (int i = 0; i < n; i++)
            {
                names[i] = "Player " + i;
                group[i] = i < piled ? i % groups : -1;
                x[i] = random.nextDouble() * PITCH_W;
                y[i] = random.nextDouble() * PITCH_H;
                heading[i] = random.nextDouble() * 2 * Math.PI;
                ox[i] = (random.nextDouble() * 2 - 1) * JOSTLE;
                oy[i] = (random.nextDouble() * 2 - 1) * JOSTLE;
            }
            // Pile-up members are listed first, so player 0 is in a pile-up whenever there is one.
            holder = names[0];
        }

        void step()
        {
            for (int g = 0; g < gx.length; g++)
            {
                gHeading[g] += (random.nextDouble() - 0.5) * 0.2;
                gx[g] += Math.cos(gHeading[g]) * SPEED * 0.25;
                gy[g] += Math.sin(gHeading[g]) * SPEED * 0.25;
                gx[g] = clamp(gx[g], 100, PITCH_W - 100);
                gy[g] = clamp(gy[g], 100, PITCH_H - 100);
            }
            for (int i = 0; i < n; i++)
            {
                if (group[i] >= 0)
                {
                    // Jostle: a random walk at running speed, held within JOSTLE of the centre.
                    double a = random.nextDouble() * 2 * Math.PI;
                    ox[i] = clamp(ox[i] + Math.cos(a) * SPEED, -JOSTLE, JOSTLE);
                    oy[i] = clamp(oy[i] + Math.sin(a) * SPEED, -JOSTLE, JOSTLE);
                    heading[i] = a;
                    x[i] = gx[group[i]] + ox[i];
                    y[i] = gy[group[i]] + oy[i];
                    continue;
                }
                heading[i] += (random.nextDouble() - 0.5) * 0.3;
                x[i] += Math.cos(heading[i]) * SPEED;
                y[i] += Math.sin(heading[i]) * SPEED;
                if (x[i] < 0 || x[i] > PITCH_W)
                {
                    heading[i] = Math.PI - heading[i];
                    x[i] = clamp(x[i], 0, PITCH_W);
                }
                if (y[i] < 0 || y[i] > PITCH_H)
                {
                    heading[i] = -heading[i];
                    y[i] = clamp(y[i], 0, PITCH_H);
                }
            }
        }

        int orientation(int i)
        {
            int o = (int) Math.round(heading[i] / (2 * Math.PI) * 2048) % 2048;
            return o < 0 ? o + 2048 : o;
        }
    }

    private static double clamp(double v, double lo, double hi)
    {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Mean, p95 and max of per-frame nanoseconds, in ms. */
    static final class Stats
    {
        final double mean;
        final double p95;
        final double max;

        Stats(long[] nanos)
        {
            long[] sorted = nanos.clone();
            Arrays.sort(sorted);
            long sum = 0;
            for (long v : sorted)
            {
                sum += v;
            }
            mean = sum / 1e6 / sorted.length;
            p95 = sorted[(int) Math.ceil(sorted.length * 0.95) - 1] / 1e6;
            max = sorted[sorted.length - 1] / 1e6;
        }

        @Override
        public String toString()
        {
            return String.format(Locale.ROOT, "%7.3f | %7.3f | %7.3f", mean, p95, max);
        }
    }

    /** One run: per-frame nanos of each timed part. */
    static final class Run
    {
        long[] build;
        long[] contacts;
        long[] sampler;
        long[] total;
        int overlappingPairs;
        int meshTriangles;
    }

    /** Runs warmup + frames of one configuration; display picks the ContactTracker mode. */
    static Run run(Body body, int n, double share, boolean display)
    {
        Pitch pitch = new Pitch(n, share, 1234L + n * 31 + Math.round(share * 100));
        ContactTracker tracker = new ContactTracker((sx, sy) -> new int[]{(int) sx / TILE, (int) sy / TILE, 0});
        ReplaySampler sampler = new ReplaySampler();
        Set<String> holders = Collections.singleton(pitch.holder);
        Run run = new Run();
        run.build = new long[frames];
        run.contacts = new long[frames];
        run.sampler = new long[frames];
        run.total = new long[frames];
        long overlaps = 0;
        int total = warmup + frames;
        for (int frame = 0; frame < total; frame++)
        {
            pitch.step();
            int tick = frame / FRAMES_PER_TICK;

            long t0 = System.nanoTime();
            Map<String, PosedMesh> meshes = new HashMap<>();
            for (int i = 0; i < n; i++)
            {
                meshes.put(pitch.names[i], PosedMesh.from(body.xs, body.ys, body.zs, body.count,
                    body.f1, body.f2, body.f3, body.faces, body.transparencies, body.colors3,
                    pitch.orientation(i), (int) pitch.x[i], (int) pitch.y[i]));
            }
            long t1 = System.nanoTime();
            tracker.update(meshes, holders, frame * 20L, tick, display);
            tracker.takeFinished();
            long t2 = System.nanoTime();
            List<ReplaySampler.PlayerState> states = new ArrayList<>(n);
            for (int i = 0; i < n; i++)
            {
                states.add(new ReplaySampler.PlayerState(pitch.names[i], (int) pitch.x[i], (int) pitch.y[i],
                    pitch.orientation(i), 1234, frame % 20, 824, frame % 12));
            }
            sampler.frame(frame, states, Collections.emptyList());
            long t3 = System.nanoTime();

            if (frame >= warmup)
            {
                int k = frame - warmup;
                run.build[k] = t1 - t0;
                run.contacts[k] = t2 - t1;
                run.sampler[k] = t3 - t2;
                run.total[k] = t3 - t0;
                overlaps += tracker.overlaps().size();
                run.meshTriangles = meshes.get(pitch.holder).triangles;
            }
        }
        run.overlappingPairs = (int) Math.round((double) overlaps / frames);
        return run;
    }

    @Test
    public void perFrameCost() throws IOException
    {
        Body body = humanoid();
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "RFL stress benchmark: %d frames after %d warm-up, %d-triangle meshes, "
            + "Java %s%n%n", frames, warmup, body.faces, System.getProperty("java.version")));
        out.append("Each part: mean | p95 | max ms per frame. contacts = ContactTracker.update; total = build + "
            + "contacts + sampler.\n\n");
        out.append(" N | pile | mode    | pairs |  build (mean | p95 | max)  | contacts (mean | p95 | max) | "
            + "sampler (mean | p95 | max) |  total (mean | p95 | max)\n");
        out.append("---|------|---------|-------|----------------------------|-----------------------------|"
            + "----------------------------|---------------------------\n");
        for (int n : PLAYERS)
        {
            for (double share : PILE_UP_SHARES)
            {
                for (boolean display : new boolean[]{true, false})
                {
                    Run r = run(body, n, share, display);
                    out.append(String.format(Locale.ROOT, "%2d | %3d%% | %-7s | %5d | %s | %s | %s | %s%n", n,
                        Math.round(share * 100), display ? "display" : "handegg", r.overlappingPairs,
                        new Stats(r.build), new Stats(r.contacts), new Stats(r.sampler), new Stats(r.total)));
                }
            }
        }
        System.out.println(out);
        Path report = Paths.get("build", "reports", "benchmark", "stress.md");
        Files.createDirectories(report.getParent());
        Files.write(report, out.toString().getBytes(StandardCharsets.UTF_8));
    }
}
