package com.caseware.templateupdates.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** Growing delay between attempts with +-20 % jitter (research R7). */
public final class RetryPolicy {

    private static final double MIN_FACTOR = 0.8;
    private static final double JITTER_SPAN = 0.4;

    private final RandomGenerator random;

    public RetryPolicy(RandomGenerator random) {
        this.random = random;
    }

    /** Delay before the next attempt, or empty when {@code attemptsSpent} reached the maximum. */
    public Optional<Duration> nextDelay(int attemptsSpent, int maxAttempts, List<Duration> baseDelays) {
        if (attemptsSpent >= maxAttempts) {
            return Optional.empty();
        }
        double factor = MIN_FACTOR + JITTER_SPAN * random.nextDouble();
        long nanos = Math.round(baseDelays.get(attemptsSpent - 1).toNanos() * factor);
        return Optional.of(Duration.ofNanos(nanos));
    }
}
