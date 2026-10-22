package edu.itc.se.lab01.task5;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

class Account {
    final int id;
    private long balance;
    final ReentrantLock lock = new ReentrantLock();   // used by TryLockBank only

    Account(int id, long balance) { this.id = id; this.balance = balance; }
    long balance() { return balance; }
    void withdraw(long a) { balance -= a; }
    void deposit(long a) { balance += a; }
}

interface Bank {
    void transfer(Account from, Account to, long amount) throws InterruptedException;
}

// BUG: lock order depends on argument order -> circular wait
class BrokenBank implements Bank {
    public void transfer(Account from, Account to, long amount) {
        synchronized (from) {
            synchronized (to) {
                if (from.balance() >= amount) { from.withdraw(amount); to.deposit(amount); }
            }
        }
    }
}

// Fix A: always lock the lower id first
class OrderedBank implements Bank {
    public void transfer(Account a, Account b, long amount) {
        Account first = a.id < b.id ? a : b;
        Account second = a.id < b.id ? b : a;
        synchronized (first) {
            synchronized (second) {
                if (a.balance() >= amount) { a.withdraw(amount); b.deposit(amount); }
            }
        }
    }
}

// Fix B: tryLock with timeout; release the first lock before backing off
class TryLockBank implements Bank {
    final AtomicLong retries = new AtomicLong();

    public void transfer(Account from, Account to, long amount) throws InterruptedException {
        while (true) {
            if (from.lock.tryLock(10, TimeUnit.MILLISECONDS)) {
                try {
                    if (to.lock.tryLock(10, TimeUnit.MILLISECONDS)) {
                        try {
                            if (from.balance() >= amount) { from.withdraw(amount); to.deposit(amount); }
                            return;
                        } finally { to.lock.unlock(); }
                    }
                } finally { from.lock.unlock(); }          // released before back-off
            }
            retries.incrementAndGet();
            Thread.sleep(ThreadLocalRandom.current().nextInt(1, 5));   // random back-off
        }
    }
}

public class Main {
    static final int WORKERS = 8, ACCOUNTS = 4, TRANSFERS = 100_000;

    static Account[] newAccounts() {
        Account[] acc = new Account[ACCOUNTS];
        for (int i = 0; i < acc.length; i++) acc[i] = new Account(i, 1_000);
        return acc;
    }

    static long total(Account[] accounts) {
        long t = 0;
        for (Account a : accounts) t += a.balance();
        return t;
    }

    // Daemon watchdog: every second ask the JVM for deadlocked threads
    static void startWatchdog() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        Thread w = new Thread(() -> {
            while (true) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                long[] ids = mx.findDeadlockedThreads();
                if (ids != null) {
                    StringBuilder names = new StringBuilder();
                    for (ThreadInfo ti : mx.getThreadInfo(ids)) {
                        if (names.length() > 0) names.append(", ");
                        names.append(ti.getThreadName());
                    }
                    System.out.println("[watchdog] DEADLOCK detected between: " + names);
                    return;
                }
            }
        }, "watchdog");
        w.setDaemon(true);
        w.start();
    }

    // 8 workers x 100,000 transfers each
    static void benchmark(String label, Bank bank) throws InterruptedException {
        Account[] accounts = newAccounts();
        LongAdder transfers = new LongAdder();
        Thread[] workers = new Thread[WORKERS];
        long t0 = System.nanoTime();
        for (int w = 0; w < workers.length; w++) {
            workers[w] = new Thread(() -> {
                var rnd = ThreadLocalRandom.current();
                try {
                    for (int i = 0; i < TRANSFERS; i++) {
                        Account a = accounts[rnd.nextInt(accounts.length)];
                        Account b;
                        do { b = accounts[rnd.nextInt(accounts.length)]; } while (b == a);
                        bank.transfer(a, b, rnd.nextInt(1, 50));
                        transfers.increment();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "transfer-" + w);
            workers[w].start();
        }
        for (Thread t : workers) t.join();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String extra = (bank instanceof TryLockBank tl) ? " retries=" + tl.retries.get() : "";
        System.out.printf("%s transfers=%d%s total=%d (expected %d) in %d ms%n",
                label, transfers.sum(), extra, total(accounts), 1_000L * ACCOUNTS, ms);
    }

    // Graceful shutdown: workers loop until interrupted
    static void shutdown() throws InterruptedException {
        Account[] accounts = newAccounts();
        TryLockBank bank = new TryLockBank();
        long[] counts = new long[WORKERS];
        Thread[] workers = new Thread[WORKERS];
        for (int w = 0; w < workers.length; w++) {
            final int id = w;
            workers[w] = new Thread(() -> {
                var rnd = ThreadLocalRandom.current();
                while (!Thread.currentThread().isInterrupted()) {
                    Account a = accounts[rnd.nextInt(accounts.length)];
                    Account b;
                    do { b = accounts[rnd.nextInt(accounts.length)]; } while (b == a);
                    try {
                        bank.transfer(a, b, rnd.nextInt(1, 50));
                        counts[id]++;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();   // restore the flag, loop exits
                    }
                }
            }, "transfer-" + w);
            workers[w].start();
        }

        Thread.sleep(2000);
        System.out.println("[main] interrupting " + WORKERS + " workers after 2000 ms");
        for (Thread t : workers) t.interrupt();

        boolean allStopped = true;
        for (int w = 0; w < workers.length; w++) {
            workers[w].join(1000);
            boolean alive = workers[w].isAlive();
            allStopped &= !alive;
            System.out.println(workers[w].getName() + " stopped after " + counts[w]
                    + " transfers (alive=" + alive + ")");
        }
        System.out.printf("all workers stopped=%b total=%d (expected %d)%n",
                allStopped, total(accounts), 1_000L * ACCOUNTS);
    }

    public static void main(String[] args) throws InterruptedException {
        String mode = args.length > 0 ? args[0] : "broken";
        switch (mode) {
            case "broken" -> {
                System.out.println("PID = " + ProcessHandle.current().pid()
                        + "  (in another terminal: jstack <pid>)");
                startWatchdog();
                benchmark("BrokenBank", new BrokenBank());   // hangs - Ctrl+C
            }
            case "ordered" -> benchmark("OrderedBank", new OrderedBank());
            case "trylock" -> benchmark("TryLockBank", new TryLockBank());
            case "shutdown" -> shutdown();
            default -> System.out.println("usage: java Main.java broken|ordered|trylock|shutdown");
        }
    }
}