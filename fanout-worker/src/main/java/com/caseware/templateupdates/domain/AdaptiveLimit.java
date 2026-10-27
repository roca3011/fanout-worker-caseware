package com.caseware.templateupdates.domain;

/**
 * AIMD concurrency limit (research R1). Not synchronized: it is only used under the lock of
 * {@code ConcurrencyLimiter}.
 *
 * <p>{@code effective} starts unbounded, so the real limit is the configured one (which can change
 * at runtime) until the first drop.
 */
public final class AdaptiveLimit {

    private int effective = Integer.MAX_VALUE;
    private long generation;
    private int successes;

    public int limit(int configured) {
        return Math.min(effective, configured);
    }

    public long generation() {
        return generation;
    }

    /** Returns true when this response lowered the limit (it is a new drop, so one alert). */
    public boolean onOverloaded(long callGeneration, int configured) {
        if (callGeneration != generation) {
            return false;
        }
        effective = Math.max(1, limit(configured) / 2);
        generation++;
        successes = 0;
        return true;
    }

    public void onNormal(int configured) {
        if (effective >= configured) {
            return;
        }
        successes++;
        if (successes >= effective) {
            effective++;
            successes = 0;
        }
    }
}
