package com.rfl.replay;

import sh.yumekui.toolkit.text.PlayerNames;
import com.rfl.incomplete.Incomplete;
import com.rfl.log.PluginEntry;
import com.rfl.teams.Teams;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.runelite.client.util.Text;

/**
 * Every replay line shape the plugin writes, in one place, so it reads against the replay spec
 * (the rfl-pages replay format spec, §2). Each line is
 * a {@link LinkedHashMap} with {@code "t"} first, so Gson keeps the key order the spec shows;
 * {@code team} lines are built as JSON text because Gson drops null map values.
 *
 * <p>Pure: no client, no IO.
 */
public final class ReplayLines
{
    /** Longest overhead chat text kept in a {@code chat} line. */
    static final int CHAT_MAX = 80;
    /** The file format version in {@code hdr.v}. */
    static final int FORMAT_VERSION = 1;
    /** {@code hdr.models}: model lines carry the geometry, so the viewer needs no bundle (spec §2.3). */
    static final int MODELS_INLINE = 2;

    private ReplayLines()
    {
    }

    private static Map<String, Object> line(String type)
    {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", type);
        return line;
    }

    private static Map<String, Object> line(String type, int cycle)
    {
        Map<String, Object> line = line(type);
        line.put("cyc", cycle);
        return line;
    }

    /** {@code {"t":"hdr","v":1,"rev":..,"world":..,"rsn":..,"at":..,"cyc":..,"models":2}} */
    static Map<String, Object> hdr(int revision, int world, String rsn, long atMs, int cycle)
    {
        Map<String, Object> hdr = line("hdr");
        hdr.put("v", FORMAT_VERSION);
        hdr.put("rev", revision);
        hdr.put("world", world);
        hdr.put("rsn", rsn);
        hdr.put("at", atMs);
        hdr.put("cyc", cycle);
        hdr.put("models", MODELS_INLINE);
        return hdr;
    }

    /** {@code {"t":"tick","cyc":..,"tick":..}} */
    static Map<String, Object> tick(int cycle, int tick)
    {
        Map<String, Object> line = line("tick", cycle);
        line.put("tick", tick);
        return line;
    }

    /** {@code {"t":"spawn","cyc":..,"i":..,"name":..}} */
    static Map<String, Object> spawn(int cycle, int index, String name)
    {
        Map<String, Object> line = line("spawn", cycle);
        line.put("i", index);
        line.put("name", name);
        return line;
    }

    /** {@code {"t":"despawn","cyc":..,"i":..}} */
    static Map<String, Object> despawn(int cycle, int index)
    {
        Map<String, Object> line = line("despawn", cycle);
        line.put("i", index);
        return line;
    }

    /** {@code {"t":"f","cyc":..,"p":[[i,x,y,orient,anim,animFrame,pose,poseFrame],...]}} */
    static Map<String, Object> poses(int cycle, List<int[]> rows)
    {
        Map<String, Object> line = line("f", cycle);
        line.put("p", rows);
        return line;
    }

    /** {@code {"t":"pm","cyc":..,"p":[[i,modelId],...]}} */
    static Map<String, Object> playerModels(int cycle, List<int[]> rows)
    {
        Map<String, Object> line = line("pm", cycle);
        line.put("p", rows);
        return line;
    }

    /** {@code {"t":"spot","cyc":..,"i":..,"s":[[id,frame,height],...]}} from flat triples. */
    static Map<String, Object> spots(int cycle, int index, int[] flatTriples)
    {
        List<int[]> triples = new ArrayList<>(flatTriples.length / 3);
        for (int at = 0; at + 2 < flatTriples.length; at += 3)
        {
            triples.add(new int[] { flatTriples[at], flatTriples[at + 1], flatTriples[at + 2] });
        }
        Map<String, Object> line = line("spot", cycle);
        line.put("i", index);
        line.put("s", triples);
        return line;
    }

    /** {@code {"t":"tt","cyc":..,"p":[[i,x,y],...]}}: true tiles. */
    static Map<String, Object> trueTiles(int cycle, List<int[]> rows)
    {
        Map<String, Object> line = line("tt", cycle);
        line.put("p", rows);
        return line;
    }

    /** {@code {"t":"app","cyc":..,"i":..,"g":..,"eq":[..],"col":[..]}} */
    static Map<String, Object> appearance(int cycle, int index, Appearance appearance)
    {
        Map<String, Object> line = line("app", cycle);
        line.put("i", index);
        line.put("g", appearance.gender);
        line.put("eq", appearance.equipment);
        line.put("col", appearance.colors);
        return line;
    }

    /**
     * {@code {"t":"ball","cyc":..,"id":..,"sc":..,"x":..,"y":..,"z":..,"o":..,"m":..}}; {@code sc}
     * is the projectile's start cycle, and {@code m} is left out when there is no model.
     */
    static Map<String, Object> ball(int cycle, Ball ball, int modelId)
    {
        Map<String, Object> line = line("ball", cycle);
        line.put("id", ball.id);
        line.put("sc", ball.startCycle);
        line.put("x", ball.x);
        line.put("y", ball.y);
        line.put("z", ball.z);
        line.put("o", ball.orient);
        if (modelId >= 0)
        {
            line.put("m", modelId);
        }
        return line;
    }

    /**
     * {@code {"t":"locs","locs":[[modelId, localX, localY, groundHeight, locId, kind], ...],
     * "names":{"<locId>":"<name>"}}}: more rows for the first pitch, with the names of the loc ids
     * these rows introduce.
     */
    public static Map<String, Object> locs(List<Object[]> rows, Map<String, String> names)
    {
        Map<String, Object> line = line("locs");
        line.put("locs", rows);
        line.put("names", names);
        return line;
    }

    /**
     * {@code {"t":"ev","cyc":..,"e":{..}}}; an incomplete with a known catch cycle gets
     * {@code "cyc":<catch cycle>} and {@code "catchCyc":<catch cycle>}, others {@code lastCycle}.
     */
    static Map<String, Object> event(int lastCycle, Object collisionOrIncomplete)
    {
        Map<String, Object> line = line("ev");
        // An incomplete is stamped with its catch cycle (the projectile disappearing), not the
        // frame it was ruled on; it may be a tick earlier than the line before it.
        int catchCycle = collisionOrIncomplete instanceof Incomplete
            ? ((Incomplete) collisionOrIncomplete).catchCyc : -1;
        line.put("cyc", catchCycle >= 0 ? catchCycle : lastCycle);
        if (catchCycle >= 0)
        {
            line.put("catchCyc", catchCycle);
        }
        line.put("e", collisionOrIncomplete);
        return line;
    }

    /**
     * {@code {"t":"chat","cyc":..,"i":..,"text":..}} for a player {@code sampler} has spawned, the
     * text without colour or img tags and at most {@link #CHAT_MAX} characters; null when there is
     * no recording, the player isn't sampled, or no text is left.
     */
    static Map<String, Object> chat(ReplaySampler sampler, int cycle, String name, String text)
    {
        if (sampler == null || name == null || text == null)
        {
            return null;
        }
        int index = sampler.spawnedIndex(name);
        if (index < 0)
        {
            return null;
        }
        String plain = Text.removeTags(text).trim();
        if (plain.isEmpty())
        {
            return null;
        }
        if (plain.length() > CHAT_MAX)
        {
            // Never split a surrogate pair.
            int end = Character.isHighSurrogate(plain.charAt(CHAT_MAX - 1)) ? CHAT_MAX - 1 : CHAT_MAX;
            plain = plain.substring(0, end);
        }
        Map<String, Object> line = line("chat", cycle);
        line.put("i", index);
        line.put("text", plain);
        return line;
    }

    /**
     * {@code {"t":"team","cyc":..,"i":..,"team":"A"|"B"|null}}, built by hand so the null stays
     * (Gson drops null map values).
     */
    static String team(int cycle, int index, Teams.Team team)
    {
        return "{\"t\":\"team\",\"cyc\":" + cycle + ",\"i\":" + index + ",\"team\":"
            + (team == null ? "null" : "\"" + team.name() + "\"") + "}";
    }

    /** A frame's lines with a {@link #team} line right after each {@code spawn}, for that player. */
    static List<?> withTeams(List<Map<String, Object>> lines, Function<String, Teams.Team> teamOf)
    {
        boolean spawns = false;
        for (Map<String, Object> line : lines)
        {
            spawns |= "spawn".equals(line.get("t"));
        }
        if (!spawns)
        {
            // Most frames: no copy.
            return lines;
        }
        List<Object> out = new ArrayList<>(lines.size() + 2);
        for (Map<String, Object> line : lines)
        {
            out.add(line);
            if ("spawn".equals(line.get("t")))
            {
                out.add(team((Integer) line.get("cyc"), (Integer) line.get("i"),
                    teamOf.apply((String) line.get("name"))));
            }
        }
        return out;
    }

    /**
     * {@link #team} lines for the sampled players matching {@code name} ({@link PlayerNames#matchKey}), or for
     * every sampled player when {@code name} is null, each with {@code teamOf}'s team.
     */
    static List<String> teams(ReplaySampler sampler, int cycle, String name, Function<String, Teams.Team> teamOf)
    {
        List<String> out = new ArrayList<>();
        String key = name == null ? null : PlayerNames.matchKey(name);
        if (key == null && name != null)
        {
            // A blank name matches nobody.
            return out;
        }
        for (String sampled : sampler.spawnedNames())
        {
            if (key == null || key.equals(PlayerNames.matchKey(sampled)))
            {
                out.add(team(cycle, sampler.spawnedIndex(sampled), teamOf.apply(sampled)));
            }
        }
        return out;
    }

    /** {@code {"t":"plugins","cyc":..,"list":[{"name":..,"enabled":..,"source":..}]}} */
    static Map<String, Object> plugins(int cycle, List<PluginEntry> list)
    {
        Map<String, Object> line = line("plugins", cycle);
        line.put("list", list);
        return line;
    }

    /** {@code {"t":"plugin_toggle","cyc":..,"name":..,"enabled":..}} */
    public static Map<String, Object> pluginToggle(int cycle, String name, boolean enabled)
    {
        Map<String, Object> line = line("plugin_toggle", cycle);
        line.put("name", name);
        line.put("enabled", enabled);
        return line;
    }

    /** Model lines are serialised on the writer's thread; every other line at once. */
    static boolean isModelLine(Map<String, Object> line)
    {
        return "model".equals(line.get("t"));
    }
}
