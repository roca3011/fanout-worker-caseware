package com.caseware.templateupdates.application;

import com.caseware.templateupdates.application.ConcurrencyLimiter.Permit;
import com.caseware.templateupdates.application.ConcurrencyLimiter.Response;
import com.caseware.templateupdates.application.port.out.AlertSink;
import com.caseware.templateupdates.application.port.out.EngagementIndex;
import com.caseware.templateupdates.application.port.out.EngagementSystem;
import com.caseware.templateupdates.application.port.out.WorkQueue;
import com.caseware.templateupdates.application.port.out.WorkResults;
import com.caseware.templateupdates.domain.CapacitySplit;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.RetryBudget;
import com.caseware.templateupdates.domain.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.random.RandomGenerator;

/**
 * Dispatcher: claims ready entries while there is room under the concurrency limit and hands each
 * call to an executor (virtual threads in production). {@link #dispatchOnce()} is one step without
 * threads of its own; {@link #run()} is the thin loop around it (research R12).
 */
public final class FanOutWorker implements AutoCloseable {

    private static final Duration DEFAULT_IDLE_POLL = Duration.ofMillis(200);
    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private final WorkQueue queue;
    private final ItemProcessor processor;
    private final ConcurrencyLimiter limiter;
    private final SettingsHolder settings;
    private final Clock clock;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final Duration idlePoll;
    private final Duration closeTimeout;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition drained = lock.newCondition();
    private final AtomicInteger running = new AtomicInteger();
    private volatile boolean closed;
    private Thread loop;

    FanOutWorker(
            WorkQueue queue,
            ItemProcessor processor,
            ConcurrencyLimiter limiter,
            SettingsHolder settings,
            Clock clock,
            Executor executor,
            ExecutorService ownedExecutor,
            Duration idlePoll,
            Duration closeTimeout) {
        this.queue = queue;
        this.processor = processor;
        this.limiter = limiter;
        this.settings = settings;
        this.clock = clock;
        this.executor = executor;
        this.ownedExecutor = ownedExecutor;
        this.idlePoll = idlePoll;
        this.closeTimeout = closeTimeout;
    }

    /** Production wiring: one virtual thread per call. */
    public static FanOutWorker create(
            WorkQueue queue,
            WorkResults results,
            EngagementIndex index,
            EngagementSystem system,
            AlertSink alerts,
            SettingsHolder settings,
            Clock clock,
            RandomGenerator random) {
        var owned = Executors.newVirtualThreadPerTaskExecutor();
        return assemble(queue, results, index, system, alerts, settings, clock, random, owned, owned);
    }

    public static FanOutWorker create(
            WorkQueue queue,
            WorkResults results,
            EngagementIndex index,
            EngagementSystem system,
            AlertSink alerts,
            SettingsHolder settings,
            Clock clock,
            RandomGenerator random,
            Executor executor) {
        return assemble(queue, results, index, system, alerts, settings, clock, random, executor, null);
    }

    private static FanOutWorker assemble(
            WorkQueue queue,
            WorkResults results,
            EngagementIndex index,
            EngagementSystem system,
            AlertSink alerts,
            SettingsHolder settings,
            Clock clock,
            RandomGenerator random,
            Executor executor,
            ExecutorService owned) {
        var limiter = new ConcurrencyLimiter(settings);
        var processor = new ItemProcessor(queue, results, index, system, alerts, settings, limiter, clock,
                new RetryPolicy(random), new RetryBudget(clock));
        return new FanOutWorker(queue, processor, limiter, settings, clock, executor, owned, DEFAULT_IDLE_POLL,
                DEFAULT_CLOSE_TIMEOUT);
    }

    public synchronized void start() {
        if (loop == null && !closed) {
            loop = Thread.ofVirtual().name("fanout-dispatcher").start(this::run);
        }
    }

    void run() {
        while (!closed) {
            if (dispatchOnce()) {
                continue;
            }
            try {
                limiter.awaitChange(idlePoll);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** One dispatch step; returns true when a call was handed to the executor. */
    boolean dispatchOnce() {
        if (closed) {
            return false;
        }
        var now = clock.instant();
        var current = settings.current();
        return CapacitySplit.next(
                        limiter.currentLimit(),
                        current.urgentShare(),
                        limiter.inFlight(Priority.URGENT),
                        limiter.inFlight(Priority.INITIAL_LOAD),
                        queue.hasReady(Priority.URGENT, now),
                        queue.hasReady(Priority.INITIAL_LOAD, now))
                .map(priority -> claimAndSubmit(priority, now, current.leaseDuration()))
                .orElse(false);
    }

    private boolean claimAndSubmit(Priority priority, Instant now, Duration leaseDuration) {
        Optional<Permit> permit = limiter.tryAcquire(priority);
        if (permit.isEmpty()) {
            return false;
        }
        var lease = queue.claim(priority, now, leaseDuration);
        if (lease.isEmpty()) {
            limiter.release(permit.get(), Response.NEUTRAL);
            return false;
        }
        submit(lease.get(), permit.get());
        return true;
    }

    private void submit(Lease lease, Permit permit) {
        running.incrementAndGet();
        executor.execute(() -> {
            try {
                processor.process(lease, permit);
            } finally {
                finished();
            }
        });
    }

    private void finished() {
        if (running.decrementAndGet() == 0) {
            lock.lock();
            try {
                drained.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /** Stops claiming, waits for calls in flight up to the close timeout, confirms nothing else. */
    @Override
    public void close() {
        closed = true;
        joinLoop();
        awaitDrained();
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }

    private synchronized void joinLoop() {
        if (loop == null) {
            return;
        }
        try {
            loop.join(closeTimeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitDrained() {
        long remaining = closeTimeout.toNanos();
        lock.lock();
        try {
            while (running.get() > 0 && remaining > 0) {
                remaining = drained.awaitNanos(remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }
}
