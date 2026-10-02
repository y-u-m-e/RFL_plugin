package com.rfl.game;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Generates the three-word, hyphen-joined passphrase offered when hosting a game (spec §6, e.g.
 * {@code brave-otter-lamp}), from a bundled word list — never a network call.
 */
public final class Passphrases
{
    private static final String WORD_LIST_RESOURCE = "/com/rfl/passphrase-words.txt";

    private static final List<String> WORDS = loadWords();

    private Passphrases()
    {
    }

    /**
     * @param random source of randomness (inject a seeded {@link Random} in tests)
     * @return three lowercase words from the bundled list, joined by {@code -}
     */
    public static String generate(final Random random)
    {
        return pick(random) + "-" + pick(random) + "-" + pick(random);
    }

    private static String pick(final Random random)
    {
        return WORDS.get(random.nextInt(WORDS.size()));
    }

    private static List<String> loadWords()
    {
        try (InputStream in = Passphrases.class.getResourceAsStream(WORD_LIST_RESOURCE))
        {
            if (in == null)
            {
                throw new IllegalStateException("Missing bundled resource " + WORD_LIST_RESOURCE);
            }
            final List<String> words = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    final String word = line.trim();
                    if (!word.isEmpty())
                    {
                        words.add(word);
                    }
                }
            }
            return Collections.unmodifiableList(words);
        }
        catch (final IOException e)
        {
            throw new IllegalStateException("Failed to load " + WORD_LIST_RESOURCE, e);
        }
    }
}
