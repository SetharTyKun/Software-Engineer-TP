package edu.itc.se.lab01.task3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

record Order(int id, String item, int qty) {
    static final Order POISON = new Order(-1, "POISON", 0);
    boolean isPoison() { return id == -1; }
}

interface OrderQueue {
    void put(Order order) throws InterruptedException;
    Order take() throws InterruptedException;
}

// Adapter so a java.util.concurrent queue can be used where OrderQueue is expected
class BlockingOrderQueue implements OrderQueue {
    private final java.util.concurrent.BlockingQueue<Order> q;
    BlockingOrderQueue(int capacity) { this.q = new java.util.concurrent.ArrayBlockingQueue<>(capacity); }
    public void put(Order o) throws InterruptedException { q.put(o); }
    public Order take() throws InterruptedException { return q.take(); }
}

class BoundedBuffer implements OrderQueue {
    private final java.util.ArrayDeque<Order> items = new java.util.ArrayDeque<>();
    private final int capacity;
    public BoundedBuffer(int capacity) { this.capacity = capacity; }

    @Override public synchronized void put(Order order) throws InterruptedException {
        if (items.size() == capacity) wait();          // full -> wait
        items.add(order);
        if (items.size() > capacity) throw new IllegalStateException("capacity exceeded");
        notify();
    }

    @Override public synchronized Order take() throws InterruptedException {
        if (items.isEmpty()) wait();                   // empty -> wait
        Order o = items.remove();
        notify();
        return o;
    }
}

class Producer implements Runnable {
    private final OrderQueue queue;
    private final int startId, count;

    Producer(OrderQueue queue, int startId, int count) {
        this.queue = queue;
        this.startId = startId;
        this.count = count;
    }

    @Override public void run() {
        try {
            for (int i = startId; i < startId + count; i++) {
                queue.put(new Order(i, "item-" + i, ThreadLocalRandom.current().nextInt(1, 6)));
                Thread.sleep(ThreadLocalRandom.current().nextInt(0, 21));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

class Consumer implements Runnable {
    private final OrderQueue queue;
    private final String name;
    private final AtomicInteger processed;

    Consumer(OrderQueue queue, String name, AtomicInteger processed) {
        this.queue = queue;
        this.name = name;
        this.processed = processed;
    }

    @Override public void run() {
        try {
            while (true) {
                Order o = queue.take();
                if (o.isPoison()) {
                    System.out.println(name + " received poison pill, stopping");
                    return;
                }
                Thread.sleep(ThreadLocalRandom.current().nextInt(5, 31));
                System.out.println(name + " processed " + o);
                processed.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

public class Main {
    static final int PRODUCERS = 2, ORDERS_EACH = 50, CONSUMERS = 3, CAPACITY = 5;

    static void runScenario(OrderQueue queue) throws InterruptedException {
        long start = System.nanoTime();
        AtomicInteger processed = new AtomicInteger();
        List<Thread> producers = new ArrayList<>(), consumers = new ArrayList<>();

        for (int p = 0; p < PRODUCERS; p++)
            producers.add(new Thread(new Producer(queue, p * ORDERS_EACH + 1, ORDERS_EACH), "producer-" + (p + 1)));
        for (int c = 1; c <= CONSUMERS; c++)
            consumers.add(new Thread(new Consumer(queue, "consumer-" + c, processed), "consumer-" + c));

        consumers.forEach(Thread::start);
        producers.forEach(Thread::start);

        for (Thread t : producers) t.join();
        for (int i = 0; i < CONSUMERS; i++) queue.put(Order.POISON);   // one pill per consumer
        for (Thread t : consumers) t.join();

        long ms = (System.nanoTime() - start) / 1_000_000;
        int expected = PRODUCERS * ORDERS_EACH;
        System.out.printf("Total processed: %d (expected %d) in %d ms%n", processed.get(), expected, ms);
        if (processed.get() != expected) throw new AssertionError("lost or duplicated orders");
    }

    // Usage: java Main.java            -> hand-made BoundedBuffer, 1 run
    //        java Main.java 20         -> BoundedBuffer, 20 runs (for the notify()/if experiment)
    //        java Main.java blocking   -> same Producer/Consumer on ArrayBlockingQueue (Main2 part)
    public static void main(String[] args) throws InterruptedException {
        boolean blocking = args.length > 0 && args[0].equals("blocking");
        int runs = (args.length > 0 && !blocking) ? Integer.parseInt(args[0]) : 1;
        for (int r = 0; r < runs; r++)
            runScenario(blocking ? new BlockingOrderQueue(CAPACITY) : new BoundedBuffer(CAPACITY));
    }
}