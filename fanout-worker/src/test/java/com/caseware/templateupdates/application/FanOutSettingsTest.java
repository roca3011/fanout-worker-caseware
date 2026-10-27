package com.caseware.templateupdates.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FanOutSettingsTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(3);
    private static final Duration LEASE = Duration.ofMinutes(6);
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final List<Duration> DELAYS = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2));

    private static FanOutSettings build(
            Duration responseTimeout,
            Duration leaseDuration,
            int maxConcurrency,
            int maxAttempts,
            List<Duration> retryDelays,
            double ratio,
            Duration window,
            int minRetries,
            double urgentShare) {
        return new FanOutSettings(
                responseTimeout, leaseDuration, maxConcurrency, maxAttempts, retryDelays, ratio, window, minRetries,
                urgentShare);
    }

    private static void assertInvalid(Runnable construction) {
        assertThatThrownBy(construction::run).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsAreTheDocumentedOnes() {
        var d = FanOutSettings.defaults();
        assertThat(d.responseTimeout()).isEqualTo(TIMEOUT);
        assertThat(d.leaseDuration()).isEqualTo(LEASE);
        assertThat(d.maxConcurrency()).isEqualTo(50);
        assertThat(d.maxAttempts()).isEqualTo(3);
        assertThat(d.retryDelays()).containsExactlyElementsOf(DELAYS);
        assertThat(d.retryBudgetRatio()).isEqualTo(0.10);
        assertThat(d.retryBudgetWindow()).isEqualTo(WINDOW);
        assertThat(d.retryBudgetMinRetries()).isEqualTo(5);
        assertThat(d.urgentShare()).isEqualTo(0.80);
    }

    @Test
    void responseTimeoutExactlyHalfOfLeaseIsValidAndOneNanoMoreIsRejected() {
        build(TIMEOUT, LEASE, 50, 3, DELAYS, 0.1, WINDOW, 5, 0.8);
        assertInvalid(() -> build(TIMEOUT.plusNanos(1), LEASE, 50, 3, DELAYS, 0.1, WINDOW, 5, 0.8));
    }

    @Test
    void responseTimeoutLongerThanLeaseIsRejected() {
        assertInvalid(() -> build(Duration.ofMinutes(8), LEASE, 50, 3, DELAYS, 0.1, WINDOW, 5, 0.8));
    }

    @Test
    void nonPositiveDurationsAreRejected() {
        assertInvalid(() -> build(Duration.ZERO, LEASE, 50, 3, DELAYS, 0.1, WINDOW, 5, 0.8));
        assertInvalid(() -> build(Duration.ofSeconds(-1), LEASE, 50, 3, DELAYS, 0.1, WINDOW, 5, 0.8));
        assertInvalid(() -> build(Duration.ofSeconds(1), Duration.ZERO, 50, 3, DELAYS, 0.1, WINDOW, 5, 0.8));
        assertInvalid(() -> build(Duration.ofSeconds(1), LEASE, 50, 3, DELAYS, 0.1, Duration.ZERO, 5, 0.8));
    }

    @Test
    void concurrencyAndAttemptsAtLeastOne() {
        build(TIMEOUT, LEASE, 1, 1, List.of(), 0.1, WINDOW, 5, 0.8);
        assertInvalid(() -> build(TIMEOUT, LEASE, 0, 3, DELAYS, 0.1, WINDOW, 5, 0.8));
        assertInvalid(() -> build(TIMEOUT, LEASE, 50, 0, DELAYS, 0.1, WINDOW, 5, 0.8));
    }

    @Test
    void retryDelaysMustCoverAttemptsBePositiveAndNonDecreasing() {
        assertInvalid(() -> build(TIMEOUT, LEASE, 50, 3, List.of(Duration.ofSeconds(30)), 0.1, WINDOW, 5, 0.8));
        assertInvalid(() -> build(TIMEOUT, LEASE, 50, 3, List.of(Duration.ofSeconds(30), Duration.ZERO), 0.1, WINDOW, 5, 0.8));
        assertInvalid(() -> build(TIMEOUT, LEASE, 50, 3, List.of(Duration.ofMinutes(2), Duration.ofSeconds(30)), 0.1, WINDOW, 5, 0.8));
        build(TIMEOUT, LEASE, 50, 3, List.of(Duration.ofSeconds(30), Duration.ofSeconds(30)), 0.1, WINDOW, 5, 0.8);
    }

    @Test
    void retryDelaysAreCopied() {
        var mutable = new ArrayList<>(DELAYS);
        var settings = build(TIMEOUT, LEASE, 50, 3, mutable, 0.1, WINDOW, 5, 0.8);
        mutable.clear();
        assertThat(settings.retryDelays()).hasSize(2);
    }

    @Test
    void ratioMustBeInOpenZeroClosedOne() {
        build(TIMEOUT, LEASE, 50, 3, DELAYS, 1.0, WINDOW, 5, 0.8);
        assertInvalid(() -> build(TIMEOUT, LEASE, 50, 3, DELAYS, 0.0, WINDOW, 5, 0.8));
        assertInvalid(() -> build(TIMEOUT, LEASE, 50, 3, DELAYS, 1.01, WINDOW, 5, 0.8));
    }

    @Test
    void minRetriesNonNegative() {
        build(TIMEOUT, LEASE, 50, 3, DELAYS, 0.1, WINDOW, 0, 0.8);
        assertInvalid(() -> build(TIMEOUT, LEASE, 50, 3, DELAYS, 0.1, WINDOW, -1, 0.8));
    }

    @Test
    void urgentShareInClosedZeroOne() {
        build(TIMEOUT, LEASE, 50, 3, DELAYS, 0.1, WINDOW, 5, 0.0);
        build(TIMEOUT, LEASE, 50, 3, DELAYS, 0.1, WINDOW, 5, 1.0);
        assertInvalid(() -> build(TIMEOUT, LEASE, 50, 3, DELAYS, 0.1, WINDOW, 5, -0.01));
        assertInvalid(() -> build(TIMEOUT, LEASE, 50, 3, DELAYS, 0.1, WINDOW, 5, 1.01));
    }

    @Test
    void holderReplacesAndRejectsNull() {
        var holder = new SettingsHolder(FanOutSettings.defaults());
        var other = build(Duration.ofMinutes(1), LEASE, 20, 3, DELAYS, 0.1, WINDOW, 5, 0.8);
        holder.replace(other);
        assertThat(holder.current()).isSameAs(other);
        assertThatThrownBy(() -> holder.replace(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SettingsHolder(null)).isInstanceOf(NullPointerException.class);
    }
}
