package sh.yumekui.toolkit.text;

/** Text for Swing's HTML labels. */
public final class Html
{
    private Html()
    {
    }

    /** Escapes a plain string so a Swing HTML label shows it as written. */
    public static String escape(String text)
    {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
