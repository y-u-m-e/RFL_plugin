package sh.yumekui.toolkit.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import sh.yumekui.toolkit.io.IoErrors;
import sh.yumekui.toolkit.overlay.TilePainter;
import java.awt.Color;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import org.junit.Test;

/**
 * The small text and colour helpers: {@link PlayerNames#matchKey}, {@link Html#escape},
 * {@link IoErrors#reason} and {@link TilePainter#withAlpha}.
 */
public class TextToolsTest
{
    @Test
    public void namesMatchHowRuneLiteComparesThem()
    {
        assertEquals("ref bob", PlayerNames.matchKey("Ref_Bob"));
        assertEquals(PlayerNames.matchKey("ref bob"), PlayerNames.matchKey("REF-BOB"));
        assertNull(PlayerNames.matchKey(null));
        assertNull("blank has no key", PlayerNames.matchKey(" "));
    }

    @Test
    public void htmlEscapingShowsMarkupAsWritten()
    {
        assertEquals("&lt;b&gt;Tom &amp; &quot;Jerry&#39;s&quot;&lt;/b&gt;", Html.escape("<b>Tom & \"Jerry's\"</b>"));
    }

    @Test
    public void ioReasonsAreShortWordsNotPaths()
    {
        assertEquals("access denied", IoErrors.reason(new AccessDeniedException("C:\\some\\long\\path")));
        assertEquals("the file-system reason wins", "disk full",
            IoErrors.reason(new FileSystemException("C:\\x", null, "disk full")));
        assertEquals("illegal state", IoErrors.reason(new IllegalStateException()));
        assertEquals("boom", IoErrors.reason(new IllegalStateException("boom")));
    }

    @Test
    public void withAlphaKeepsTheColourAndClampsTheAlpha()
    {
        assertEquals(new Color(10, 20, 30, 50), TilePainter.withAlpha(new Color(10, 20, 30, 200), 50));
        assertEquals(255, TilePainter.withAlpha(Color.RED, 999).getAlpha());
        assertEquals(0, TilePainter.withAlpha(Color.RED, -5).getAlpha());
    }
}
