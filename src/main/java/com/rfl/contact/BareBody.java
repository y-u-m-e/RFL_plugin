package com.rfl.contact;

import sh.yumekui.toolkit.model.VertexSnapshot;

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
public final class BareBody
{
    /** Kit slots in identity-kit body part order: male body part = index, female = index + 7. */
    private static final KitType[] SLOTS = {
        KitType.HAIR, KitType.JAW, KitType.TORSO, KitType.ARMS, KitType.HANDS, KitType.LEGS, KitType.BOOTS,
    };
    static final int FEMALE_BODY_PART_OFFSET = 7;
    /** {@code PlayerComposition#getGender()} for a female body. */
    private static final int FEMALE = 1;
    /** Distinct (gender, kits) lit models kept; one per outfit in view is plenty. */
    private static final int MODEL_CACHE_SIZE = 64;
    /** LinkedHashMap's default sizing; the third argument (true) orders by access, for the LRU eviction. */
    private static final int CACHE_INITIAL_CAPACITY = 16;
    private static final float CACHE_LOAD_FACTOR = 0.75f;
    /**
     * The lighting the client gives player models (ambient, contrast, light direction x/y/z), as in
     * RuneLite's player model building. Only the vertices are used here, but lighting builds the Model.
     */
    private static final int LIGHT_AMBIENT = 64;
    private static final int LIGHT_CONTRAST = 850;
    private static final int LIGHT_X = -30;
    private static final int LIGHT_Y = -50;
    private static final int LIGHT_Z = -30;

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

    private volatile Map<Integer, Kit> kits;
    private volatile Map<Integer, Integer> defaults;
    /** A built, lit body and its vertices before any pose. */
    private static final class CachedBody
    {
        final Model model;
        final VertexSnapshot pristine;

        CachedBody(Model model)
        {
            this.model = model;
            this.pristine = VertexSnapshot.of(model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(),
                model.getVerticesCount(), model.getFaceTransparencies());
        }

        /** Undoes any pose the client applied to the model in place, so this pose starts clean. */
        void restore()
        {
            pristine.restoreInto(model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(),
                model.getFaceTransparencies());
        }
    }

    /** Most recently used last; the eldest goes once there are more than {@link #MODEL_CACHE_SIZE}. */
    private final Map<String, CachedBody> models = new LinkedHashMap<String, CachedBody>(CACHE_INITIAL_CAPACITY,
        CACHE_LOAD_FACTOR, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedBody> eldest)
        {
            return size() > MODEL_CACHE_SIZE;
        }
    };

    @Inject
    BareBody(Client client, Gson gson)
    {
        this.client = client;
        this.gson = gson;
    }

    /** Reads the bundled identity-kit table. Classpath IO: call off the client thread (startUp). */
    public void load()
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
        for (Map.Entry<Integer, Kit> kit : kits.entrySet())
        {
            if (kit.getValue().selectable)
            {
                result.merge(kit.getValue().bodyPart, kit.getKey(), Math::min);
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
        int[] kitIds = new int[SLOTS.length];
        for (int i = 0; i < SLOTS.length; i++)
        {
            kitIds[i] = composition.getKitId(SLOTS[i]);
        }
        boolean female = composition.getGender() == FEMALE;
        int[] ids = resolve(table, defaults, female, kitIds);
        String key = female + Arrays.toString(ids);
        CachedBody body = models.get(key);
        if (body == null)
        {
            Model built = build(table, ids);
            if (built == null)
            {
                return null;
            }
            body = new CachedBody(built);
            models.put(key, body);
        }
        // Whether the client poses a copy or the model itself is not documented, so the base is put
        // back to its pre-pose vertices every time: a pose in place can then never accumulate.
        body.restore();
        int animationId = player.getAnimation();
        int poseId = player.getPoseAnimation();
        Animation animation = animationId == -1 ? null : client.loadAnimation(animationId);
        Animation pose = poseId == -1 ? null : client.loadAnimation(poseId);
        // Returns the base itself when both are null. The result is only valid until the next
        // pose: read it at once.
        return client.applyTransformations(body.model, animation, player.getAnimationFrame(),
            pose, player.getPoseAnimationFrame());
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
        return client.mergeModels(parts.toArray(new ModelData[0])).light(LIGHT_AMBIENT, LIGHT_CONTRAST, LIGHT_X, LIGHT_Y, LIGHT_Z);
    }
}
