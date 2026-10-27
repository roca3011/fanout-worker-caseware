package com.caseware.templateupdates.fixtures;

import com.caseware.templateupdates.application.port.out.EngagementSystem;
import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.LoadOutcome;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class ScriptedEngagementSystem implements EngagementSystem {

    private final Map<EngagementId, Deque<LoadOutcome>> scripts = new ConcurrentHashMap<>();
    private final Map<EngagementId, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger seq = new AtomicInteger();
    private final AtomicReference<Duration> lastTimeout = new AtomicReference<>();
    private volatile Semaphore gate;
    private volatile Consumer<EngagementRef> onCall = ref -> { };

    public void script(EngagementId id, LoadOutcome... outcomes) {
        var queue = new ArrayDeque<LoadOutcome>();
        for (LoadOutcome outcome : outcomes) {
            queue.add(outcome);
        }
        scripts.put(id, queue);
    }

    public void holdCalls() {
        gate = new Semaphore(0);
    }

    public void release(int permits) {
        gate.release(permits);
    }

    public void releaseAll() {
        var current = gate;
        gate = null;
        if (current != null) {
            current.release(Integer.MAX_VALUE / 2);
        }
    }

    public void onCall(Consumer<EngagementRef> hook) {
        this.onCall = hook;
    }

    public boolean awaitInFlight(int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (inFlight.get() < expected) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            TimeUnit.MILLISECONDS.sleep(2);
        }
        return true;
    }

    public Duration lastTimeout() {
        return lastTimeout.get();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public void resetMaxInFlight() {
        maxInFlight.set(inFlight.get());
    }

    public int maxInFlight() {
        return maxInFlight.get();
    }

    public int callsTo(EngagementId id) {
        var counter = calls.get(id);
        return counter == null ? 0 : counter.get();
    }

    public int totalCalls() {
        return calls.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    @Override
    public LoadOutcome load(EngagementRef engagement, Duration responseTimeout) {
        lastTimeout.set(responseTimeout);
        calls.computeIfAbsent(engagement.id(), k -> new AtomicInteger()).incrementAndGet();
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            onCall.accept(engagement);
            var current = gate;
            if (current != null) {
                current.acquireUninterruptibly();
            }
            return nextOutcome(engagement.id());
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private LoadOutcome nextOutcome(EngagementId id) {
        var script = scripts.get(id);
        if (script != null) {
            synchronized (script) {
                var next = script.size() > 1 ? script.poll() : script.peek();
                if (next != null) {
                    return next;
                }
            }
        }
        return new LoadOutcome.Loaded("v1", seq.incrementAndGet());
    }
}
