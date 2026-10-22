package edu.itc.se.lab01.task1;

class Countdown extends Thread {
    public Countdown(String name) {
        super(name);
    }

    @Override
    public void run() {
        try {
            for(int i = 3; i >= 1; i--) {
                System.out.println(getName() + ": " + i);
                Thread.sleep(200);
            }
            System.out.println(getName() + ": liftoff!");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

class CountdownTask implements Runnable {
    @Override
    public void run() {
        String name = Thread.currentThread().getName();
        try {
            for(int i = 3; i >= 1; i--) {
                System.out.println(name + ": " + i);
                Thread.sleep(200);
            }
            System.out.println(name + ": liftoff!");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

public class Main {
    public static void main(String[] args) throws InterruptedException  {
        boolean sequential = args.length > 0 && args[0].equals("--sequential");

        // subclass
        Thread apollo = new Countdown("apollo");
        // runnable
        Thread gemini = new Thread(new CountdownTask(), "gemini");
        // lambda
        Thread mercury = new Thread(() -> new CountdownTask().run(), "mercury");
        // virtual thread
        Thread artemis = Thread.ofVirtual().name("artemis").unstarted(new CountdownTask());

        Thread[] all = { apollo, gemini, mercury, artemis };
        for(Thread t: all) {
            if (sequential) {
                t.run();
            } else {
                t.start();
            }
        }

        System.out.println("Mission control: all launched");

        for(Thread t: all) {
            t.join();
        }

        System.out.println("Mission control: all landed");

        for (Thread t: all) {
            System.out.println(t.getName() + " virtual=" + t.isVirtual() + " state=" + t.getState());
        }
    }
}

// start() vs run():
// start() creates a new thread and runs run() on it, so the four countdowns interleave
// and "all launched" prints before they finish. run() is an ordinary method call on the
// main thread: each countdown finishes before the next begins ("main: 3, 2, 1" instead
// of the thread's own name), "all launched" prints only after all of them, and the
// threads never leave state NEW. Calling run() on the virtual thread (artemis) does
// nothing at all, because a virtual thread has no run() body to call directly,
// so in --sequential mode only apollo and two "main" countdowns are printed.