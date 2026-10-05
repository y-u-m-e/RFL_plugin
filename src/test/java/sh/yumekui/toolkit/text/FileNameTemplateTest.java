package sh.yumekui.toolkit.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Map;
import org.junit.Test;

/**
 * {@link FileNameTemplate} on its own: token expansion, the rules that make a name safe on every
 * system, and numbering a taken name.
 */
public class FileNameTemplateTest
{
    private static final int MAX = 100;

    @Test
    public void tokensExpandAndUnknownTokensOrStrayBracesGiveNoName()
    {
        Map<String, String> tokens = Map.of("world", "354", "player", "Ref Bob");
        assertEquals("w354 Ref Bob", FileNameTemplate.expand("w{world} {player}", tokens, MAX));
        assertNull(FileNameTemplate.expand("{nope}", tokens, MAX));
        assertNull(FileNameTemplate.expand("a}b", tokens, MAX));
        assertNull(FileNameTemplate.expand("{world", tokens, MAX));
        assertNull("blank templates give no name", FileNameTemplate.expand("  ", tokens, MAX));
    }

    @Test
    public void sanitisingDropsWhatNoFileSystemAllows()
    {
        assertNull(FileNameTemplate.sanitize(null, MAX));
        assertNull("only dots and spaces: nothing left", FileNameTemplate.sanitize(" . ", MAX));
        assertEquals("control characters become _", "a_b", FileNameTemplate.sanitize("a\u0000b", MAX));
        assertEquals("a reserved device name gets a leading _", "_con.txt", FileNameTemplate.sanitize("con.txt", MAX));
        assertEquals("cut, then trimmed again", "ab", FileNameTemplate.sanitize("ab.cd", 3));
    }

    @Test
    public void aTakenNameIsNumberedBeforeTheSuffix()
    {
        assertEquals("game-2.ndjson.gz", FileNameTemplate.numbered("game.ndjson.gz", 2, ".ndjson.gz"));
        assertEquals("a name without the suffix is numbered at the end", "x-3",
            FileNameTemplate.numbered("x", 3, ".ndjson.gz"));
    }
}
