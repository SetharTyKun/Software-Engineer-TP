package edu.itc.se.lab01.challenge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WordCountTest {

    @Test
    void parallelMatchesSingleThreaded(@TempDir Path dir) throws Exception {
        DataGenerator.generate(dir, 20, 5_000, 1L);
        Map<String, Long> expected = WordCount.countSingleThreaded(dir);

        for (boolean virtual : new boolean[]{false, true}) {
            WordCount wc = new WordCount(dir, 4, virtual, 5, 60);
            wc.verbose = false;
            wc.csv = null;
            assertTrue(wc.run(), "run should complete");
            assertEquals(expected, wc.snapshot(), "virtual=" + virtual);
        }
    }

    @Test
    void stopsWithinThreeSecondsOnTimeout(@TempDir Path dir) throws Exception {
        DataGenerator.generate(dir, 5_000, 4_000, 2L);      // too much work for 1 worker in 1 s

        WordCount wc = new WordCount(dir, 1, false, 3, 1);   // --timeout 1
        wc.verbose = false;
        wc.csv = null;

        long t0 = System.nanoTime();
        boolean complete = wc.run();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertFalse(complete, "result should be partial");
        assertTrue(ms < 3_000, "stopped after " + ms + " ms");
    }
}