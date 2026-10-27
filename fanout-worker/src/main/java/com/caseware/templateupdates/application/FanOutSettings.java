package com.caseware.templateupdates.application;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record FanOutSettings(
        Duration responseTimeout,
        Duration leaseDuration,
        int maxConcurrency,
        int maxAttempts,
        List<Duration> retryDelays,
        double retryBudgetRatio,
        Duration retryBudgetWindow,
        int retryBudgetMinRetries,
        double urgentShare) {

    public FanOutSettings {
        requirePositive(responseTimeout, "responseTimeout");
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(retryBudgetWindow, "retryBudgetWindow");
        require(responseTimeout.compareTo(leaseDuration.dividedBy(2)) <= 0,
                "responseTimeout must be <= leaseDuration / 2");
        require(maxConcurrency >= 1, "maxConcurrency must be >= 1");
        require(maxAttempts >= 1, "maxAttempts must be >= 1");
        retryDelays = List.copyOf(retryDelays);
        require(retryDelays.size() >= maxAttempts - 1, "retryDelays must have at least maxAttempts - 1 entries");
        for (int i = 0; i < retryDelays.size(); i++) {
            requirePositive(retryDelays.get(i), "retryDelays[" + i + "]");
            require(i == 0 || retryDelays.get(i).compareTo(retryDelays.get(i - 1)) >= 0,
                    "retryDelays must not decrease");
        }
        require(retryBudgetRatio > 0 && retryBudgetRatio <= 1, "retryBudgetRatio must be in (0, 1]");
        require(retryBudgetMinRetries >= 0, "retryBudgetMinRetries must be >= 0");
        require(urgentShare >= 0 && urgentShare <= 1, "urgentShare must be in [0, 1]");
    }

    public static FanOutSettings defaults() {
        return new FanOutSettings(
                Duration.ofMinutes(3),
                Duration.ofMinutes(6),
                50,
                3,
                List.of(Duration.ofSeconds(30), Duration.ofMinutes(2)),
                0.10,
                Duration.ofMinutes(5),
                5,
                0.80);
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        require(!value.isNegative() && !value.isZero(), name + " must be > 0");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
