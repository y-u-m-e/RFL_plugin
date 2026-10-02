package com.rfl;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.kit.KitType;

/**
 * A player's posed body without equipment: their identity kits (hair, jaw, torso, arms, hands,
 * legs, feet) merged into one model and posed with their current animation and pose frames. A slot
 * whose kit is hidden under equipment (getKitId -1) uses that gender's default kit for the part.
 * Client thread only, except {@link #load()}.
 */
@Slf4j
@Singleton
final class BareBody
{
    /** Kit slots in identity-kit body part order: male body part = index, female = index + 7. */
    private static final KitType[] SLOTS = {
        KitType.HAIR, KitType.JAW, KitType.TORSO, KitType.ARMS, KitType.HANDS, KitType.LEGS, KitType.BOOTS,
    };
    static final int FEMALE_BODY_PART_OFFSET = 7;
    /** Distinct (gender, kits) lit models kept; one per outfit in view is plenty. */
    private static final int MODEL_CACHE_SIZE = 64;
    private static final long LOG_INTERVAL_MS = 10_000;

    static final class Kit
    {
        int bodyPart;
        int[] models;
        boolean selectable;
    }

    static final class KitFile
    {
        String source;
        Map<Integer, Kit> kits;
    }

    private final Client client;
    private final Gson gson;
    private final RflConfig config;

    private volatile Map<Integer, Kit> kits;
    private volatile Map<Integer, Integer> defaults;
    private final Map<String, Model> models = new LinkedHashMap<String, Model>(16, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Model> eldest)
        {
            return size() > MODEL_CACHE_SIZE;
        }
    };

    private long nanos;
    private int calls;
    private int frames;
    private long lastLog;
    private volatile double msPerFrame;

    @Inject
    BareBody(Client client, Gson gson, RflConfig config)
    {
        this.client = client;
        this.gson = gson;
        this.config = config;
    }

    /** Reads the bundled identity-kit table. Classpath IO: call off the client thread (startUp). */
    void load()
    {
        if (kits != null)
        {
            return;
        }
        try (InputStream in = BareBody.class.getResourceAsStream("identkits.json"))
        {
            if (in == null)
            {
                log.warn("identkits.json missing; bare-body hitboxes unavailable");
                return;
            }
            KitFile file = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), KitFile.class);
            defaults = defaults(file.kits);
            kits = file.kits;
        }
        catch (Exception e)
        {
            log.warn("could not read identkits.json; bare-body hitboxes unavailable", e);
        }
    }

    /** Lowest selectable kit id per body part: the default look for a part hidden under equipment. */
    static Map<Integer, Integer> defaults(Map<Integer, Kit> kits)
    {
        Map<Integer, Integer> result = new HashMap<>();
        for (Map.Entry<Integer, Kit> e : kits.entrySet())
        {
            if (e.getValue().selectable)
            {
                result.merge(e.getValue().bodyPart, e.getKey(), Math::min);
            }
        }
        return result;
    }

    /**
     * Kit id per {@link #SLOTS} entry: the player's own kit when it is a known kit for that body
     * part, else the default for the part, else -1.
     */
    static int[] resolve(Map<Integer, Kit> kits, Map<Integer, Integer> defaults, boolean female, int[] kitIds)
    {
        int[] ids = new int[kitIds.length];
        for (int i = 0; i < kitIds.length; i++)
        {
            int part = i + (female ? FEMALE_BODY_PART_OFFSET : 0);
            Kit kit = kits.get(kitIds[i]);
            ids[i] = kit != null && kit.bodyPart == part ? kitIds[i] : defaults.getOrDefault(part, -1);
        }
        return ids;
    }

    /** The player's posed bare body, or null when not ready. Read its vertices immediately. */
    Model posed(Player player)
    {
        Map<Integer, Kit> table = kits;
        PlayerComposition composition = player.getPlayerComposition();
        if (table == null || composition == null)
        {
            return null;
        }
        long start = System.nanoTime();
        try
        {
            int[] kitIds = new int[SLOTS.length];
            for (int i = 0; i < SLOTS.length; i++)
            {
                kitIds[i] = composition.getKitId(SLOTS[i]);
            }
            boolean female = composition.getGender() == 1;
            int[] ids = resolve(table, defaults, female, kitIds);
            String key = female + Arrays.toString(ids);
            Model base = models.get(key);
            if (base == null)
            {
                base = build(table, ids);
                if (base == null)
                {
                    return null;
                }
                models.put(key, base);
            }
            int animationId = player.getAnimation();
            int poseId = player.getPoseAnimation();
            Animation animation = animationId == -1 ? null : client.loadAnimation(animationId);
            Animation pose = poseId == -1 ? null : client.loadAnimation(poseId);
            // Returns base itself when both are null; otherwise the client should pose a copy (its
            // shared sequence model, as Player.getModel does), leaving base untouched - unverified
            // in game. Either way the result is only valid until the next pose: read it at once.
            return client.applyTransformations(base, animation, player.getAnimationFrame(),
                pose, player.getPoseAnimationFrame());
        }
        finally
        {
            nanos += System.nanoTime() - start;
            calls++;
        }
    }

    /** Call once per client frame after posing; logs the average cost every ~10 s (Debug logging). */
    void endFrame()
    {
        frames++;
        long now = System.currentTimeMillis();
        if (now - lastLog < LOG_INTERVAL_MS)
        {
            return;
        }
        msPerFrame = nanos / 1e6 / frames;
        if (config.debugLogging() && lastLog != 0)
        {
            log.info("[RFL debug] bare bodies: {} ms/frame avg over {} frames ({} posed, {} outfits cached)",
                String.format("%.3f", msPerFrame), frames, calls, models.size());
        }
        nanos = 0;
        calls = 0;
        frames = 0;
        lastLog = now;
    }

    /** Average ms per frame spent posing bare bodies over the last ~10 s window. */
    double msPerFrame()
    {
        return msPerFrame;
    }

    void reset()
    {
        models.clear();
    }

    private Model build(Map<Integer, Kit> table, int[] ids)
    {
        List<ModelData> parts = new ArrayList<>();
        for (int id : ids)
        {
            Kit kit = id < 0 ? null : table.get(id);
            for (int modelId : kit == null || kit.models == null ? new int[0] : kit.models)
            {
                ModelData data = client.loadModelData(modelId);
                if (data == null)
                {
                    return null;
                }
                parts.add(data);
            }
        }
        if (parts.isEmpty())
        {
            return null;
        }
        // Same lighting the client gives player models; only the vertices matter here.
        return client.mergeModels(parts.toArray(new ModelData[0])).light(64, 850, -30, -50, -30);
    }
}
