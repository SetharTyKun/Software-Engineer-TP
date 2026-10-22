package edu.itc.se.lab01.task4;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

class PrimeCounter implements Callable<Long> {
    private final long from, to;
    PrimeCounter(long from, long to) { this.from = from; this.to = to; }

    @Override public Long call() { return Main.countPrimes(from, to); }
}

class FakeDownload implements Callable<String> {
    private final String url;
    private final int id;
    private final long sleepMs;

    FakeDownload(String url, int id, long sleepMs) {
        this.url = url;
        this.id = id;
        this.sleepMs = sleepMs;
    }

    @Override public String call() throws Exception {
        Thread.sleep(sleepMs);                       // simulated network
        if (id % 50 == 0) throw new IOException("boom");   // every 50th fails
        return url + " -> " + url.length();
    }
}

public class Main {
    static long countPrimes(long from, long to) {
        long count = 0;
        for (long n = Math.max(from, 2); n < to; n++) {
            boolean prime = true;
            for (long d = 2; d * d <= n; d++) if (n % d == 0) { prime = false; break; }
            if (prime) count++;
        }
        return count;
    }

    static final int DOWNLOADS = 500;

    // Submits 500 downloads to the given pool and prints an ok/failed/timedOut summary.
    static void runDownloads(String label, ExecutorService pool) throws InterruptedException {
        long t0 = System.nanoTime();
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 1; i <= DOWNLOADS; i++) {
            long sleep = (i == 1) ? 5_000 : 200;     // task 1 is the slow one, awaited first
            futures.add(pool.submit(new FakeDownload("http://site/" + i, i, sleep)));
        }

        int ok = 0, failed = 0, timedOut = 0;
        for (Future<String> f : futures) {
            try {
                f.get(1, TimeUnit.SECONDS);
                ok++;
            } catch (ExecutionException e) {          // e.getCause() is the IOException
                failed++;
            } catch (TimeoutException e) {
                f.cancel(true);                       // interrupt the sleeping task
                timedOut++;
            }
        }
        System.out.printf("%-44s: ok=%d failed=%d timedOut=%d in %d ms%n",
                label, ok, failed, timedOut, (System.nanoTime() - t0) / 1_000_000);
    }

    public static void main(String[] args) throws Exception {
        final long LIMIT = 4_000_000, CHUNKS = 16;

        long t0 = System.nanoTime();
        long single = countPrimes(0, LIMIT);
        System.out.printf("single-threaded: %d primes in %d ms%n", single, (System.nanoTime() - t0) / 1_000_000);

        int cores = Runtime.getRuntime().availableProcessors();
        t0 = System.nanoTime();
        try (ExecutorService pool = Executors.newFixedThreadPool(cores)) {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < CHUNKS; i++) {
                futures.add(pool.submit(new PrimeCounter(i * LIMIT / CHUNKS, (i + 1) * LIMIT / CHUNKS)));
            }
            long total = 0;
            for (Future<Long> f : futures) total += f.get();
            System.out.printf("pool (%d threads): %d primes in %d ms%n",
                    cores, total, (System.nanoTime() - t0) / 1_000_000);
            if (total != single) throw new AssertionError("parallel count differs from single-threaded");
        }

        // TODO: I/O-bound part with 500 FakeDownload tasks on platform pool vs virtual threads
        // TODO: failures every 50th task, one 5-second task with a 1-second timeout
        try (ExecutorService platform = Executors.newFixedThreadPool(20)) {
            runDownloads("downloads on fixed(20) platform threads", platform);
        }
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            runDownloads("downloads on virtual threads", virtual);
        }
    }
}