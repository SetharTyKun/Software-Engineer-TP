package edu.itc.se.lab01.challenge;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class WordCount {
    private static final Pattern NON_LETTERS = Pattern.compile("[^\\p{L}]+");
    private static final Path POISON = Path.of("__POISON__");

    private final BlockingQueue<Path> queue = new ArrayBlockingQueue<>(16);   // back-pressure
    private final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();
    private final AtomicInteger filesDone = new AtomicInteger();
    private final LongAdder wordsDone = new LongAdder();
    private final AtomicBoolean stopping = new AtomicBoolean();

    private final Path dir;
    private final int workers;
    private final boolean virtual;
    private final int top;
    private final long timeoutSec;

    // package-private so tests can silence output and skip the CSV
    boolean verbose = true;
    Path csv = Path.of("result.csv");

    private volatile long startNanos;
    private volatile long totalFiles;
    private volatile ExecutorService pool;
    private volatile ScheduledExecutorService reporter;
    private volatile Thread scanner;

    public WordCount(Path dir, int workers, boolean virtual, int top, long timeoutSec) {
        this.dir = dir;
        this.workers = workers;
        this.virtual = virtual;
        this.top = top;
        this.timeoutSec = timeoutSec;
    }

    /** @return true if every file was processed, false if the result is [PARTIAL]. */
    boolean run() throws InterruptedException, IOException {
        totalFiles = countFiles();
        startNanos = System.nanoTime();
        pool = virtual ? Executors.newVirtualThreadPerTaskExecutor()
                       : Executors.newFixedThreadPool(workers);
        reporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reporter");
            t.setDaemon(true);
            return t;
        });
        scanner = Thread.ofPlatform().name("scanner").start(this::scan);
        if (verbose) reporter.scheduleAtFixedRate(this::report, 500, 500, TimeUnit.MILLISECONDS);

        Thread hook = new Thread(this::shutdown, "shutdown-hook");   // Ctrl+C
        Runtime.getRuntime().addShutdownHook(hook);

        CountDownLatch latch = new CountDownLatch(workers);
        for (int i = 0; i < workers; i++) {
            pool.submit(() -> {
                try { work(); } finally { latch.countDown(); }
            });
        }

        boolean complete = false;
        try {
            boolean finished = latch.await(timeoutSec, TimeUnit.SECONDS);
            if (finished && stopping.compareAndSet(false, true)) {
                scanner.join();
                complete = true;
                printResult(false);
            } else if (!finished) {
                if (verbose) System.out.printf("[%.1fs] deadline reached - stopping%n", elapsedSec());
                shutdown();                       // same method the hook uses
            }
            // else: Ctrl+C hook is already stopping and prints the [PARTIAL] result
        } finally {
            pool.shutdownNow();
            reporter.shutdownNow();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // JVM is already shutting down (Ctrl+C): the hook is running, nothing to remove
            }
        }
        return complete;
    }

    private long countFiles() throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    private void scan() {
        try (Stream<Path> s = Files.walk(dir)) {
            Iterator<Path> it = s.filter(Files::isRegularFile).iterator();
            while (it.hasNext()) queue.put(it.next());   // blocks when the queue is full
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();          // cancelled: workers are interrupted too
            return;
        } catch (IOException | UncheckedIOException e) {
            System.err.println("scanner error: " + e.getMessage());
        }
        try {
            for (int i = 0; i < workers; i++) queue.put(POISON);   // one pill per worker
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void work() {
        try {
            while (!Thread.currentThread().isInterrupted()) {   // checked between files
                Path p = queue.take();
                if (p == POISON) return;
                if (countFile(p)) filesDone.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();                 // restore the flag
        }
    }

    /** @return false if interrupted half-way through the file. */
    private boolean countFile(Path p) {
        try (Stream<String> lines = Files.lines(p)) {
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                if (Thread.currentThread().isInterrupted()) return false;
                words(it.next(), w -> {
                    counts.computeIfAbsent(w, k -> new LongAdder()).increment();
                    wordsDone.increment();
                });
            }
            return true;
        } catch (IOException | UncheckedIOException e) {
            if (Thread.currentThread().isInterrupted()) return false;   // cancelled mid-file, not an error
            System.err.println("skipping " + p + ": " + e.getMessage());
            return true;
        }
    }

    static void words(String line, Consumer<String> sink) {
        for (String w : NON_LETTERS.split(line)) {
            if (!w.isEmpty()) sink.accept(w.toLowerCase(Locale.ROOT));
        }
    }

    private double elapsedSec() { return (System.nanoTime() - startNanos) / 1e9; }

    private String mode() { return virtual ? "virtual" : "platform(" + workers + ")"; }

    private List<Map.Entry<String, Long>> sortedCounts() {
        List<Map.Entry<String, Long>> list = new ArrayList<>();
        counts.forEach((w, c) -> list.add(Map.entry(w, c.sum())));   // snapshot first, then sort
        list.sort(Map.Entry.<String, Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.<String, Long>comparingByKey()));
        return list;
    }

    // Weakly consistent view of the map: fine for progress output, never locks the map.
    private void report() {
        try {
            double secs = elapsedSec();
            long words = wordsDone.sum();
            StringBuilder top3 = new StringBuilder();
            sortedCounts().stream().limit(3).forEach(e ->
                    top3.append(e.getKey()).append('(').append(String.format("%,d", e.getValue())).append(") "));
            System.out.printf("[%.1fs] files %d/%d  words %,d  %,d w/s  top: %s%n",
                    secs, filesDone.get(), totalFiles, words, (long) (words / secs), top3.toString().trim());
        } catch (RuntimeException e) {
            System.err.println("reporter error: " + e);   // otherwise the schedule silently stops
        }
    }

    void shutdown() {
        if (!stopping.compareAndSet(false, true)) return;   // runs exactly once
        try {
            scanner.interrupt();
            pool.shutdownNow();                             // interrupts every worker
            pool.awaitTermination(2, TimeUnit.SECONDS);
            scanner.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        reporter.shutdownNow();
        printResult(true);
    }

    private void printResult(boolean partial) {
        List<Map.Entry<String, Long>> sorted = sortedCounts();
        long total = sorted.stream().mapToLong(Map.Entry::getValue).sum();
        if (verbose) {
            System.out.printf("%s files=%d words=%,d elapsed=%.2fs mode=%s%n",
                    partial ? "[PARTIAL]" : "DONE ", filesDone.get(), total, elapsedSec(), mode());
            System.out.println(" rank  word            count     share");
            for (int i = 0; i < Math.min(top, sorted.size()); i++) {
                Map.Entry<String, Long> e = sorted.get(i);
                System.out.printf(" %4d  %-12s %,10d  %6.2f %%%n", i + 1, e.getKey(), e.getValue(),
                        100.0 * e.getValue() / total);
            }
        }
        if (csv != null) {
            try (BufferedWriter w = Files.newBufferedWriter(csv)) {
                w.write("rank,word,count,percent\n");
                int rank = 1;
                for (Map.Entry<String, Long> e : sorted) {
                    w.write(rank++ + "," + e.getKey() + "," + e.getValue() + ","
                            + String.format(Locale.ROOT, "%.4f", 100.0 * e.getValue() / total) + "\n");
                }
                if (verbose) System.out.println("result.csv written");
            } catch (IOException e) {
                System.err.println("could not write " + csv + ": " + e.getMessage());
            }
        }
    }

    /** Sorted copy of the counts (used by the tests). */
    Map<String, Long> snapshot() {
        Map<String, Long> m = new TreeMap<>();
        counts.forEach((w, c) -> m.put(w, c.sum()));
        return m;
    }

    /** Single-threaded reference implementation. */
    static Map<String, Long> countSingleThreaded(Path dir) throws IOException {
        Map<String, Long> m = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile).toList();
        }
        for (Path p : files) {
            try (Stream<String> lines = Files.lines(p)) {
                lines.forEach(l -> words(l, w -> m.merge(w, 1L, Long::sum)));
            }
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: WordCount <dir> [--top N] [--timeout SECONDS] [--workers N] [--virtual]");
            return;
        }
        Path dir = Path.of(args[0]);
        int top = 10, workers = Runtime.getRuntime().availableProcessors();
        long timeout = 60;
        boolean virtual = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--top" -> top = Integer.parseInt(args[++i]);
                case "--timeout" -> timeout = Long.parseLong(args[++i]);
                case "--workers" -> workers = Integer.parseInt(args[++i]);
                case "--virtual" -> virtual = true;
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        new WordCount(dir, workers, virtual, top, timeout).run();
    }
}