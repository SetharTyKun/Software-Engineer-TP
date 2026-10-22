# Lab 01: Multithreading in Java

## Task 1: Creating and running threads

`start()` creates a new thread that runs `run()` concurrently. Calling `run()`
directly just executes the method on the calling (main) thread, so with
`--sequential` every countdown finishes before the next begins and
"Mission control: all launched" prints last. `join()` makes `main` wait until each
thread has finished, so "all landed" prints after all four countdowns.

## Task 2: Fixing a race condition

### Why is synchronizing only `increment()` not enough?

`get()` reads `count` without taking the same lock, so it has no happens-before
relationship with the writers. A thread calling `get()` while others are still
incrementing can see a stale value (and, for a non-volatile `long`, even a torn one).

In my benchmark the bug is hidden because `join()` creates a happens-before edge
before `get()` is called. A reader that doesn't `join()` has no such guarantee.

Fix: make `get()` synchronized too, or use `volatile`, `AtomicLong` or `LongAdder`.

### Timing results (8 threads x 1,000,000 increments)

| Counter | Result | Time |
|---|---|---|
| UnsafeCounter | wrong (2,748,952) | 44 ms |
| SynchronizedCounter | 8,000,000 | 707 ms |
| LockCounter | 8,000,000 | 193 ms |
| AtomicCounter | 8,000,000 | 112 ms |
| LongAdderCounter | 8,000,000 | 17 ms |

UnsafeCounter is fast only because it is incorrect (lost updates). Of the correct
versions, `synchronized` is slowest because all 8 threads queue on one monitor.
`AtomicLong` avoids locks using compare-and-swap, but threads still contend on one
memory location. `LongAdder` is fastest because it spreads updates across several
internal cells and only adds them up in `sum()`. Timings vary between runs, so these
numbers are approximate.

## Task 3: Producer-consumer

### Why `while` + `notifyAll()`?

**`while` instead of `if`:** After `wait()` returns, the condition may be false again
(another thread may have taken the slot first, or the wakeup was spurious), so it
must be re-checked in a loop.

**`notifyAll()` instead of `notify()`:** Producers and consumers wait on the same
monitor. `notify()` wakes only one arbitrary thread, which may be the wrong kind
(e.g. a producer waking a producer), so the thread that can make progress stays
asleep. `notifyAll()` wakes everyone, and each re-checks its own condition.

### Experiment: `notify()` + `if`

I changed `notifyAll()` to `notify()` and `while` to `if`, and ran the scenario 20
times in a loop. It failed: one run processed only 57 of 100 orders and the
assertion fired. I observed `NoSuchElementException` in `take()` (a consumer woke to
an empty buffer) and `IllegalStateException: capacity exceeded` in `put()` (a
producer woke to a full buffer). Each exception killed its thread, so orders were
lost. It can also hang on other runs.

## Task 4: Thread pools, Future and virtual threads

Counting primes below 4,000,000 took 1116 ms single-threaded and 153 ms on a pool of
20 threads. 500 downloads of 200 ms each took 5221 ms on a fixed pool of 20 platform
threads (500 x 200 ms / 20) and 1008 ms on virtual threads: all 500 sleep
concurrently, because a sleeping virtual thread frees its OS thread. The remaining
time is the 1 s timeout wait on the slow task, which is cancelled with
`cancel(true)`. Every 50th download fails; the summary reports
`ok=489 failed=10 timedOut=1` and the program never crashes. Virtual threads help for
I/O-bound work, not for CPU-bound work, where the core count is the limit.

## Task 5: Deadlock and graceful shutdown

**Cause:** `transfer(from, to)` locks `from` and then `to`, so the lock order depends
on the argument order. Thread A (acc-1 -> acc-2) holds acc-1 and waits for acc-2,
while thread B (acc-2 -> acc-1) holds acc-2 and waits for acc-1: a circular wait.
`jstack` (see `deadlock.txt`) and the watchdog both reported it.

**Fix A (lock ordering):** always lock the account with the lower id first, so a
circular wait is impossible. 800000 transfers, total 4000, 130 ms.

**Fix B (tryLock):** each account has a `ReentrantLock`. `tryLock(10 ms)` on both,
and on failure release the first lock, back off randomly and retry (this breaks
hold-and-wait). 800000 transfers, 4391 retries, total 4000, 13259 ms. It is much
slower than ordering because 8 threads contend for only 4 accounts and every failed
attempt waits up to 10 ms plus a back-off sleep.

**Graceful shutdown:** workers loop `while (!isInterrupted())`; when a blocking call
throws `InterruptedException` they restore the flag with `interrupt()` so the loop
exits. After 2 s `main` interrupted all workers, and every one stopped within 1 s
(`alive=false`). A transfer finishes under its locks, so the total stayed 4000.

## Challenge: parallel word-frequency analyser

A single scanner thread walks the directory and puts paths into a bounded
`BlockingQueue` (capacity 16, which gives back-pressure). N workers take paths and
update a shared `ConcurrentHashMap<String, LongAdder>`. A reporter thread prints
progress every 500 ms without locking the map. `--timeout` and Ctrl+C both call the
same `shutdown()` method, which interrupts the scanner and workers and prints a
`[PARTIAL]` result. Tests: a JUnit test checks that the parallel result equals a
single-threaded reference (platform and virtual threads), and another checks that
the tool stops within 3 s with `--timeout 1` on 5,000 files.

### Scaling (200 files x 200,000 words = 40,000,000 words)

| Mode | Elapsed | Speed-up vs 1 worker |
|---|---|---|
| 1 platform worker | 4.72 s | 1.0x |
| 2 platform workers | 2.24 s | 2.1x |
| 4 platform workers | 1.36 s | 3.5x |
| 8 platform workers | 1.01 s | 4.7x |
| virtual threads (20 tasks) | 0.69 s | 6.8x |

Times are from a single run on my machine and vary between runs. Scaling is close to
linear up to 2 workers and then flattens, because the single scanner thread and file
reading limit throughput. Virtual threads were fastest here only because they ran 20
tasks (one per core, the default), not because they are inherently faster: the work
is CPU-bound, so extra threads beyond the core count would not help.

### Why `LongAdder` / `merge` beats `synchronized` on the map

A `synchronized` HashMap makes every worker take one monitor for every word, so
workers run one at a time. `ConcurrentHashMap` locks only the bin being updated (and
reads of an existing key mostly don't lock), and `LongAdder.increment()` spreads
updates over several cells, so threads rarely touch the same memory.
`merge(w, 1L, Long::sum)` is also thread-safe, but it takes the bin lock on every
call and boxes a new `Long` each time; `computeIfAbsent(...).increment()` only locks
when a word is first inserted.

### Coffman conditions

A deadlock needs all four conditions. This design rules out **hold-and-wait** and
**circular wait**: a worker never holds one lock while waiting for another (the queue
and map manage their own internal locks, and my code never nests them), so no cycle
can form. Cancellation uses interruption, which breaks any blocking `put()` or
`take()`. Mutual exclusion still exists inside the queue, but alone it cannot cause a
deadlock.

## Evidence

### Deadlock detection (Task 5, watchdog)

```
PID = 30300  (in another terminal: jstack <pid>)
[watchdog] DEADLOCK detected between: transfer-0, transfer-1, transfer-2
```

The full `jstack` report is in `deadlock.txt`.

### `[PARTIAL]` result (Challenge, `--timeout 1`)

```
[0.5s] files 12/200  words 2,400,000  4,748,930 w/s  top: aaaa(190,430) baaa(49,574) caaa(34,707)
[1.0s] files 25/200  words 5,000,000  4,980,706 w/s  top: aaaa(396,280) baaa(103,345) caaa(72,196)
[1.0s] deadline reached - stopping
[PARTIAL] files=25 words=5,000,000 elapsed=1.03s mode=platform(1)
```