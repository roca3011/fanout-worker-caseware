package com.caseware.templateupdates.application;

import com.caseware.templateupdates.domain.AdaptiveLimit;
import com.caseware.templateupdates.domain.Priority;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Adaptive limit on simultaneous calls (research R1). Thread-safety: one {@link ReentrantLock}
 * guards the counters and the {@link AdaptiveLimit}; it is never held while calling the engagement
 * system (permits are acquired before and released after the call).
 */
public final class ConcurrencyLimiter {

    public record Permit(long generation, Priority priority) {}

    public enum Response { NORMAL, OVERLOADED, NEUTRAL }

    private final SettingsHolder settings;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final AdaptiveLimit adaptive = new AdaptiveLimit();
    private final Map<Priority, Integer> inFlightByPriority = new EnumMap<>(Priority.class);
    private int inFlight;

    public ConcurrencyLimiter(SettingsHolder settings) {
        this.settings = settings;
        for (Priority priority : Priority.values()) {
            inFlightByPriority.put(priority, 0);
        }
    }

    public Optional<Permit> tryAcquire(Priority priority) {
        lock.lock();
        try {
            if (inFlight >= currentLimitLocked()) {
                return Optional.empty();
            }
            inFlight++;
            inFlightByPriority.merge(priority, 1, Integer::sum);
            return Optional.of(new Permit(adaptive.generation(), priority));
        } finally {
            lock.unlock();
        }
    }

    /** Returns true when the response lowered the limit (a new drop). */
    public boolean release(Permit permit, Response response) {
        lock.lock();
        try {
            inFlight--;
            inFlightByPriority.merge(permit.priority(), -1, Integer::sum);
            boolean lowered = false;
            int configured = settings.current().maxConcurrency();
            switch (response) {
                case OVERLOADED -> lowered = adaptive.onOverloaded(permit.generation(), configured);
                case NORMAL -> adaptive.onNormal(configured);
                case NEUTRAL -> { }
            }
            changed.signalAll();
            return lowered;
        } finally {
            lock.unlock();
        }
    }

    public int currentLimit() {
        lock.lock();
        try {
            return currentLimitLocked();
        } finally {
            lock.unlock();
        }
    }

    public int inFlight() {
        lock.lock();
        try {
            return inFlight;
        } finally {
            lock.unlock();
        }
    }

    public int inFlight(Priority priority) {
        lock.lock();
        try {
            return inFlightByPriority.get(priority);
        } finally {
            lock.unlock();
        }
    }

    /** Waits until a permit is released or the timeout passes; true when it was woken up. */
    public boolean awaitChange(Duration timeout) throws InterruptedException {
        lock.lock();
        try {
            return changed.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } finally {
            lock.unlock();
        }
    }

    private int currentLimitLocked() {
        return adaptive.limit(settings.current().maxConcurrency());
    }
}
