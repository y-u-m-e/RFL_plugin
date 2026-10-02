package com.rfl.game;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public class PassphrasesTest
{
    @Test
    public void generatesThreeLowercaseWordsJoinedByHyphens()
    {
        final String passphrase = Passphrases.generate(new Random(1));

        assertTrue(passphrase, passphrase.matches("^[a-z]+-[a-z]+-[a-z]+$"));
    }

    @Test
    public void differsForDifferentSeeds()
    {
        final String first = Passphrases.generate(new Random(1));
        final String second = Passphrases.generate(new Random(2));

        assertNotEquals(first, second);
    }
}
