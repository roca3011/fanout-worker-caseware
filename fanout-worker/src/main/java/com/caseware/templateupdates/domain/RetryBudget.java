package com.caseware.templateupdates.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Retry budget over a sliding window (research R8): retries allowed =
 * max(minRetries, ratio x calls). Thread-safety: all state is guarded by its own
 * {@link ReentrantLock}; no external call is made while holding it.
 */
public final class RetryBudget {

    public enum Verdict { ALLOWED, EXHAUSTED_FIRST, EXHAUSTED }

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Instant> calls = new ArrayDeque<>();
    private final Deque<Instant> retries = new ArrayDeque<>();
    private boolean exhausted;

    public RetryBudget(Clock clock) {
        this.clock = clock;
    }

    public void recordCall(Duration window) {
        lock.lock();
        try {
            var now = clock.instant();
            evict(now, window);
            calls.addLast(now);
        } finally {
            lock.unlock();
        }
    }

    /** Asks to retry; a granted retry is counted, a refused one is not. */
    public Verdict requestRetry(Duration window, double ratio, int minRetries) {
        lock.lock();
        try {
            var now = clock.instant();
            evict(now, window);
            int allowed = Math.max(minRetries, (int) Math.floor(ratio * calls.size() + 1e-9));
            if (retries.size() < allowed) {
                retries.addLast(now);
                exhausted = false;
                return Verdict.ALLOWED;
            }
            if (exhausted) {
                return Verdict.EXHAUSTED;
            }
            exhausted = true;
            return Verdict.EXHAUSTED_FIRST;
        } finally {
            lock.unlock();
        }
    }

    int callsInWindow(Duration window) {
        lock.lock();
        try {
            evict(clock.instant(), window);
            return calls.size();
        } finally {
            lock.unlock();
        }
    }

    int retriesInWindow(Duration window) {
        lock.lock();
        try {
            evict(clock.instant(), window);
            return retries.size();
        } finally {
            lock.unlock();
        }
    }

    private void evict(Instant now, Duration window) {
        var cutoff = now.minus(window);
        while (!calls.isEmpty() && !calls.peekFirst().isAfter(cutoff)) {
            calls.removeFirst();
        }
        while (!retries.isEmpty() && !retries.peekFirst().isAfter(cutoff)) {
            retries.removeFirst();
        }
    }
}
