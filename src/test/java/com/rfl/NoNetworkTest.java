package com.rfl;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

/** The plugin is local-only: no main source may import an HTTP client or socket API. */
public class NoNetworkTest
{
    private static final List<String> FORBIDDEN = List.of("import okhttp3.", "import java.net.", "import java.net.http.",
        "import javax.net.");

    @Test
    public void mainSourcesHaveNoNetworkImports() throws IOException
    {
        Path main = Paths.get("src", "main", "java");
        assertTrue("run from the project directory", Files.isDirectory(main));
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(main))
        {
            sources = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        assertTrue(!sources.isEmpty());
        List<String> hits = new ArrayList<>();
        for (Path source : sources)
        {
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8))
            {
                for (String forbidden : FORBIDDEN)
                {
                    if (line.trim().startsWith(forbidden))
                    {
                        hits.add(source + ": " + line.trim());
                    }
                }
            }
        }
        assertTrue("network imports: " + hits, hits.isEmpty());
    }
}
