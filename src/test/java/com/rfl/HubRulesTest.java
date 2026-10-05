package com.rfl;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * The Plugin Hub's code rules, checked on every main source file (the plugin and the toolkit), so a
 * change that breaks one fails the build: no networking (the plugin is local-only), no reflection,
 * no JNI or JNA, no launching processes, no Java serialization, no dynamic class loading, no
 * {@code new Gson()} (the client's injected Gson is used), and no {@code java.awt.Desktop} (links go
 * through RuneLite's LinkBrowser). Comments and string literals are ignored, so the javadoc that
 * explains a rule doesn't trip it.
 */
public class HubRulesTest
{
    private static final Map<String, Pattern> FORBIDDEN = new LinkedHashMap<>();

    static
    {
        FORBIDDEN.put("network", Pattern.compile("import (okhttp3|java\\.net|javax\\.net)\\.|HttpURLConnection|\\bSocket\\b"));
        FORBIDDEN.put("reflection", Pattern.compile("java\\.lang\\.reflect|\\.getDeclared(Field|Method|Constructor)s?\\("
            + "|\\.setAccessible\\(|Class\\.forName\\("));
        FORBIDDEN.put("JNI/JNA", Pattern.compile("\\bnative\\s+\\w+.*\\(|com\\.sun\\.jna|System\\.load(Library)?\\("));
        FORBIDDEN.put("processes", Pattern.compile("ProcessBuilder|Runtime\\.getRuntime\\(\\)\\.exec"));
        FORBIDDEN.put("serialization", Pattern.compile("Object(Input|Output)Stream|implements[^{]*\\bSerializable\\b"));
        FORBIDDEN.put("class loading", Pattern.compile("ClassLoader|defineClass\\("));
        FORBIDDEN.put("new Gson", Pattern.compile("new Gson\\(\\)"));
        FORBIDDEN.put("Desktop", Pattern.compile("java\\.awt\\.Desktop|\\bDesktop\\.getDesktop\\("));
    }

    @Test
    public void mainSourcesKeepThePluginHubRules() throws IOException
    {
        Path main = Paths.get("src", "main", "java");
        assertTrue("run from the project directory", Files.isDirectory(main));
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(main))
        {
            sources = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        assertTrue("found the sources", !sources.isEmpty());
        List<String> hits = new ArrayList<>();
        for (Path source : sources)
        {
            String code = codeOnly(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
            for (Map.Entry<String, Pattern> rule : FORBIDDEN.entrySet())
            {
                if (rule.getValue().matcher(code).find())
                {
                    hits.add(source + ": " + rule.getKey());
                }
            }
        }
        assertTrue("Hub rules broken: " + hits, hits.isEmpty());
    }

    @Test
    public void eachRuleCatchesWhatItIsFor()
    {
        // So a rule that silently matches nothing can't pass the check above.
        Map<String, String> samples = new LinkedHashMap<>();
        samples.put("network", "import okhttp3.OkHttpClient;");
        samples.put("reflection", "field.setAccessible(true);");
        samples.put("JNI/JNA", "private native int peek(long address);");
        samples.put("processes", "new ProcessBuilder(\"cmd\").start();");
        samples.put("serialization", "new ObjectOutputStream(out);");
        samples.put("class loading", "new URLClassLoader(urls);");
        samples.put("new Gson", "Gson gson = new Gson();");
        samples.put("Desktop", "Desktop.getDesktop().browse(uri);");
        for (Map.Entry<String, String> sample : samples.entrySet())
        {
            assertTrue(sample.getKey(), FORBIDDEN.get(sample.getKey()).matcher(codeOnly(sample.getValue())).find());
        }
        assertTrue("clientThread.invoke is not reflection",
            !FORBIDDEN.get("reflection").matcher("clientThread.invoke(this::run);").find());
    }

    /** The source without block comments, line comments and string or char literals. */
    private static String codeOnly(String source)
    {
        String noBlocks = source.replaceAll("(?s)/\\*.*?\\*/", "");
        String noStrings = noBlocks.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"").replaceAll("'(?:[^'\\\\]|\\\\.)'", "''");
        return noStrings.replaceAll("//[^\n]*", "");
    }
}
