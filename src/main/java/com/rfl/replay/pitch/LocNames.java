package com.rfl.replay.pitch;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Object names for the {@code names} map of {@code pitch} and {@code locs} lines, one file at a
 * time: each loc id's name goes out once per file, with the first row that uses the id.
 *
 * <p>Client thread only.
 */
public final class LocNames
{
    private final IntFunction<String> resolver;
    /** Loc ids already named in this file. */
    private final Set<Integer> named = new HashSet<>();

    /** @param resolver a loc id's object name (the impostor's when it has one); may return null */
    public LocNames(IntFunction<String> resolver)
    {
        this.resolver = resolver == null ? id -> null : resolver;
    }

    /** The name for {@code id} the first time it is asked for in this file (blank when unknown); null after. */
    String nameIfNew(int id)
    {
        if (!named.add(id))
        {
            return null;
        }
        String name = resolver.apply(id);
        return name == null ? "" : name;
    }
}
