package com.rfl.teams;

import sh.yumekui.toolkit.text.PlayerNames;
import com.rfl.RflConfig;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;

/**
 * Local team assignments: each player is on Team A, Team B, or unassigned (the default). Only a
 * pair on opposite teams can collide or be an incomplete ({@link #opposing}); same-team pairs and
 * any pair with an unassigned player never count, so with nobody assigned nothing is detected.
 *
 * <p>Assignments change only when the user changes them (the panel's Teams view or the
 * right-click "RFL: Team A / Team B / Unassign" entries) or clicks Clear teams. They are saved
 * through {@link ConfigManager} under {@link #CONFIG_KEY} as a JSON object keyed by RSN, so they
 * survive a restart. Names are matched the way RuneLite does ({@link Text#toJagexName}, ignoring
 * case), so "Ref_Bob" and "ref bob" are one player.
 *
 * <p>Threads: any. Writes are synchronized; reads go through an immutable snapshot, so the
 * per-frame detection gate never blocks.
 */
@Singleton
public final class Teams
{
    /** Hidden setting (no ConfigItem) holding the assignments: {@code {"Ref Bob":"A","Amy":"B"}}. */
    static final String CONFIG_KEY = "teams";

    /** Team A and Team B colours, for the Teams view and the right-click entries; models are never tinted. */
    public static final Color TEAM_A_COLOR = new Color(230, 70, 76);
    public static final Color TEAM_B_COLOR = new Color(80, 140, 245);

    public enum Team
    {
        A,
        B;

        public String label()
        {
            return "Team " + name();
        }

        public Color color()
        {
            return this == A ? TEAM_A_COLOR : TEAM_B_COLOR;
        }
    }

    /** Where assignments are kept between sessions. */
    interface Store
    {
        /** The saved JSON, or null. */
        String load();

        /** Saves the JSON; null removes it. */
        void save(String json);
    }

    /** Told after every change, on the thread that made it. */
    public interface Listener
    {
        /** One player's team changed; {@code name} as the user's name for them. */
        void changed(String name);

        /** Clear teams: everyone is unassigned. */
        void cleared();
    }

    private final Gson gson;
    private final Store store;
    /** Normalised name to team. Replaced, never mutated. */
    private volatile Map<String, Team> byKey = Collections.emptyMap();
    /** Normalised name to the name as shown. Replaced, never mutated. */
    private volatile Map<String, String> shown = Collections.emptyMap();
    private final AtomicLong version = new AtomicLong();
    private volatile Listener listener;
    /**
     * Name to {@link PlayerNames#matchKey}, so the per-frame team lookup doesn't rebuild the key for every player
     * every frame. Names in view repeat; the cache is emptied whenever it reaches
     * {@link #KEY_CACHE_LIMIT}, which bounds it without any eviction bookkeeping.
     */
    private final Map<String, String> keys = new ConcurrentHashMap<>();
    /** Far more names than a house holds, so a clear is rare. */
    private static final int KEY_CACHE_LIMIT = 512;

    @Inject
    Teams(Gson gson, ConfigManager configManager)
    {
        this(gson, new Store()
        {
            @Override
            public String load()
            {
                return configManager.getConfiguration(RflConfig.GROUP, CONFIG_KEY);
            }

            @Override
            public void save(String json)
            {
                if (json == null)
                {
                    configManager.unsetConfiguration(RflConfig.GROUP, CONFIG_KEY);
                }
                else
                {
                    configManager.setConfiguration(RflConfig.GROUP, CONFIG_KEY, json);
                }
            }
        });
    }

    Teams(Gson gson, Store store)
    {
        this.gson = gson;
        this.store = store;
    }

    public void setListener(Listener listener)
    {
        this.listener = listener;
    }

    /** Only A against B counts; same team or anyone unassigned never does. */
    public static boolean opposing(Team first, Team second)
    {
        return first != null && second != null && first != second;
    }

    /** Reads the saved assignments (plugin start-up). Bad entries are skipped. */
    public synchronized void load()
    {
        Map<String, Team> teams = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        String json = store.load();
        if (json != null && !json.isEmpty())
        {
            try
            {
                JsonElement root = gson.fromJson(json, JsonElement.class);
                if (root != null && root.isJsonObject())
                {
                    for (Map.Entry<String, JsonElement> saved : ((JsonObject) root).entrySet())
                    {
                        Team team = parse(saved.getValue());
                        String key = PlayerNames.matchKey(saved.getKey());
                        if (team != null && key != null)
                        {
                            teams.put(key, team);
                            names.put(key, saved.getKey());
                        }
                    }
                }
            }
            catch (JsonParseException | IllegalStateException saved)
            {
                // A hand-edited or torn value: start with nobody assigned rather than fail.
            }
        }
        byKey = Collections.unmodifiableMap(teams);
        shown = Collections.unmodifiableMap(names);
        version.incrementAndGet();
    }

    private static Team parse(JsonElement value)
    {
        if (value == null || !value.isJsonPrimitive())
        {
            return null;
        }
        String letter = value.getAsString();
        return "A".equals(letter) ? Team.A : "B".equals(letter) ? Team.B : null;
    }

    /** Any thread: the player's team, or null when unassigned. */
    public Team team(String name)
    {
        String key = cachedKey(name);
        return key == null ? null : byKey.get(key);
    }

    /** {@link PlayerNames#matchKey}, cached per name. */
    private String cachedKey(String name)
    {
        if (name == null)
        {
            return null;
        }
        String key = keys.get(name);
        if (key == null)
        {
            key = PlayerNames.matchKey(name);
            if (key == null)
            {
                return null;
            }
            if (keys.size() >= KEY_CACHE_LIMIT)
            {
                keys.clear();
            }
            keys.put(name, key);
        }
        return key;
    }

    /**
     * Puts a player on a team ({@code team} null unassigns), saves, and tells the listener.
     *
     * @return whether anything changed
     */
    public boolean assign(String name, Team team)
    {
        String key = PlayerNames.matchKey(name);
        if (key == null)
        {
            return false;
        }
        synchronized (this)
        {
            if (byKey.get(key) == team)
            {
                return false;
            }
            Map<String, Team> teams = new LinkedHashMap<>(byKey);
            Map<String, String> names = new LinkedHashMap<>(shown);
            if (team == null)
            {
                teams.remove(key);
                names.remove(key);
            }
            else
            {
                teams.put(key, team);
                names.put(key, name);
            }
            byKey = Collections.unmodifiableMap(teams);
            shown = Collections.unmodifiableMap(names);
            version.incrementAndGet();
            save();
        }
        Listener told = listener;
        if (told != null)
        {
            told.changed(name);
        }
        return true;
    }

    /** Clear teams: unassigns everyone, removes the saved value, and tells the listener. */
    public void clear()
    {
        synchronized (this)
        {
            byKey = Collections.emptyMap();
            shown = Collections.emptyMap();
            version.incrementAndGet();
            save();
        }
        Listener told = listener;
        if (told != null)
        {
            told.cleared();
        }
    }

    private void save()
    {
        if (byKey.isEmpty())
        {
            store.save(null);
            return;
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, String> entry : new TreeMap<>(invert()).entrySet())
        {
            out.addProperty(entry.getKey(), entry.getValue());
        }
        store.save(gson.toJson(out));
    }

    /** Shown name to "A" / "B". */
    private Map<String, String> invert()
    {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Team> entry : byKey.entrySet())
        {
            out.put(shown.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue().name());
        }
        return out;
    }

    /** Any thread: every assigned player, as shown, to their team, sorted by name ignoring case. */
    public Map<String, Team> assigned()
    {
        Map<String, Team> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Team> teams = byKey;
        Map<String, String> names = shown;
        for (Map.Entry<String, Team> entry : teams.entrySet())
        {
            out.put(names.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue());
        }
        return out;
    }

    /** Any thread: changes whenever an assignment does. */
    public long version()
    {
        return version.get();
    }

    /**
     * The right-click entries for a player, as the team each would set (null for Unassign): only
     * for a player's menu entry while in a house, and only the ones that change something.
     */
    static List<Team> menuChoices(boolean inPoh, boolean playerEntry, Team current)
    {
        List<Team> out = new ArrayList<>(3);
        if (!inPoh || !playerEntry)
        {
            return out;
        }
        if (current != Team.A)
        {
            out.add(Team.A);
        }
        if (current != Team.B)
        {
            out.add(Team.B);
        }
        if (current != null)
        {
            out.add(null);
        }
        return out;
    }

    /** "RFL: Team A", "RFL: Team B", "RFL: Unassign". */
    static String menuLabel(Team team)
    {
        return "RFL: " + (team == null ? "Unassign" : team.label());
    }
}
