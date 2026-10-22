package edu.itc.se.lab01.challenge;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** Writes files of random words from a fixed 2,000-word vocabulary (skewed, so "top words" are meaningful). */
public final class DataGenerator {
    static final int VOCAB = 2000;

    private DataGenerator() {}

    static String word(int i) {                       // letters only, e.g. "aaaa", "baaa", ...
        char[] c = new char[4];
        for (int k = 0; k < 4; k++) { c[k] = (char) ('a' + i % 26); i /= 26; }
        return new String(c);
    }

    public static void generate(Path dir, int files, int wordsPerFile, long seed) throws IOException {
        Files.createDirectories(dir);
        String[] vocab = new String[VOCAB];
        for (int i = 0; i < VOCAB; i++) vocab[i] = word(i);
        Random rnd = new Random(seed);
        for (int f = 0; f < files; f++) {
            Path p = dir.resolve(String.format("file-%04d.txt", f));
            try (BufferedWriter w = Files.newBufferedWriter(p)) {
                for (int i = 0; i < wordsPerFile; i++) {
                    double r = rnd.nextDouble();
                    w.write(vocab[(int) (VOCAB * r * r * r)]);       // skew toward low indexes
                    w.write(i % 10 == 9 ? '\n' : ' ');
                }
            }
        }
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "data");
        int files = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        int words = args.length > 2 ? Integer.parseInt(args[2]) : 50_000;
        generate(dir, files, words, 42L);
        System.out.println("wrote " + files + " files x " + words + " words to " + dir);
    }
}