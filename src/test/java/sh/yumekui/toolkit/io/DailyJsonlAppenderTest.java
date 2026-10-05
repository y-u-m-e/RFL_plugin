package sh.yumekui.toolkit.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link DailyJsonlAppender}: lines go to the file of their local date, in the order appended even
 * on a multi-threaded executor, and the folder is created on the first write.
 */
public class DailyJsonlAppenderTest
{
    private static final int LINES = 500;
    private static final int THREADS = 4;
    private static final long WAIT_SECONDS = 10;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void linesLandInOrderInTheirDaysFile() throws Exception
    {
        Path dir = temp.getRoot().toPath().resolve("not-yet-created");
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        DailyJsonlAppender appender = new DailyJsonlAppender(dir, executor);
        long today = System.currentTimeMillis();
        for (int line = 0; line < LINES; line++)
        {
            String json = "{\"n\":" + line + "}";
            appender.append(() -> json, today);
        }
        appender.append(() -> "{\"tomorrow\":true}", today + DAY_MS);
        executor.shutdown();
        assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));

        List<String> expected = new ArrayList<>();
        for (int line = 0; line < LINES; line++)
        {
            expected.add("{\"n\":" + line + "}");
        }
        assertEquals(expected, Files.readAllLines(appender.dayFile(today), StandardCharsets.UTF_8));
        assertEquals(List.of("{\"tomorrow\":true}"), Files.readAllLines(appender.dayFile(today + DAY_MS),
            StandardCharsets.UTF_8));
        assertTrue("the save time is set once a line is on disk", appender.lastSavedAtMs() >= today);
    }

    @Test
    public void theFileIsNamedForTheLocalDate()
    {
        long now = System.currentTimeMillis();
        LocalDate date = LocalDate.now(ZoneId.systemDefault());
        // Run near midnight the two clocks can straddle it; then the next day's name is right too.
        String name = DailyJsonlAppender.fileName(now);
        assertTrue(name, name.equals(date + ".jsonl") || name.equals(date.plusDays(1) + ".jsonl"));
    }
}
