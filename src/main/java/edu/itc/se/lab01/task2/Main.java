package edu.itc.se.lab01.task2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;


// Three ways to fix race condition: synchronized, ReentrantLock, AtomicLong

interface Counter {
    void increment();
    long get();
}

class UnsafeCounter implements Counter {
    private long count;

    @Override
    public void increment() {
        count++;
    }

    @Override
    public long get() {
        return count;
    }
}

class SynchronizedCounter implements Counter {
    private long count;

    @Override
    public synchronized void increment() {
        count++;
    }

    @Override
    public synchronized long get() {
        return count;
    }
}

class LockCounter implements Counter {
    private final ReentrantLock lock = new ReentrantLock();
    private long count;

    @Override public void increment() {
        lock.lock();
        try { count++; } finally { lock.unlock(); }
    }
    @Override public long get() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }
}

class AtomicCounter implements Counter {
    private final AtomicLong count = new AtomicLong();
    @Override public void increment() { count.incrementAndGet(); }
    @Override public long get() { return count.get(); }
}

// Faster under contention: LongAdder spreads updates across several internal cells,
// so threads rarely fight over one memory location. sum() adds the cells up on read.
class LongAdderCounter implements Counter {
    private final LongAdder count = new LongAdder();
    @Override public void increment() { count.increment(); }
    @Override public long get() { return count.sum(); }
}

class Bench {
    static final int THREADS = 8;
    static final int ITERATIONS = 1_000_000;

    static void run(String label, Counter counter) throws InterruptedException {
        long start = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            Thread th = new Thread(() -> {
                for (int i = 0; i < ITERATIONS; i++) counter.increment();
            }, "inc-" + t);
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) th.join();
        long ms = (System.nanoTime() - start) / 1_000_000;
        long expected = (long) THREADS * ITERATIONS;
        System.out.printf("%-22s value=%,d  correct=%-5b  %d ms%n",
                label, counter.get(), counter.get() == expected, ms);
    }
}

public class Main {
    public static void main(String[] args) throws InterruptedException {
        Bench.run("UnsafeCounter", new UnsafeCounter());

        // TODO: SynchronizedCounter, LockCounter, AtomicCounter, LongAdderCounter
        Bench.run("SynchronizedCounter", new SynchronizedCounter());
        Bench.run("LockCounter", new LockCounter());
        Bench.run("AtomicCounter", new AtomicCounter());
        Bench.run("LongAdderCounter", new LongAdderCounter());
    }
}



// UnsafeCounter results (expected 8,000,000)
// run 1: UnsafeCounter          value=2,734,589  correct=false  43 ms
// run 2: UnsafeCounter          value=2,481,489  correct=false  46 ms
// run 3: UnsafeCounter          value=2,130,367  correct=false  38 ms