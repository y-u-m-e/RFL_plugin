package com.rfl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

/** The install ID is per RuneScape account (RS profile): two accounts never share one. */
public class InstallIdTest
{
    private final Map<String, String> store = new HashMap<>();

    private String idFor(final String profileKey)
    {
        return RflPlugin.installIdFor(profileKey, () -> store.get(profileKey), v -> store.put(profileKey, v));
    }

    @Test
    public void noProfileMeansNoIdAndNoWrite()
    {
        assertNull(idFor(null));
        assertEquals(0, store.size());
    }

    @Test
    public void firstUseGeneratesAndStoresAUuid()
    {
        final String id = idFor("profile-a");
        assertEquals(id, UUID.fromString(id).toString());
        assertEquals(id, store.get("profile-a"));
        assertEquals(id, idFor("profile-a"));
    }

    @Test
    public void eachAccountGetsItsOwnId()
    {
        store.put("profile-a", "stored-a");
        assertEquals("stored-a", idFor("profile-a"));
        final String b = idFor("profile-b");
        assertEquals(b, store.get("profile-b"));
        assertEquals(false, b.equals("stored-a"));
    }
}
