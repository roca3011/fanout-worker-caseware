package com.caseware.templateupdates.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.application.ConcurrencyLimiter.Permit;
import com.caseware.templateupdates.application.ConcurrencyLimiter.Response;
import com.caseware.templateupdates.domain.Priority;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConcurrencyLimiterTest {

    private static FanOutSettings withConcurrency(int maxConcurrency) {
        var d = FanOutSettings.defaults();
        return new FanOutSettings(d.responseTimeout(), d.leaseDuration(), maxConcurrency, d.maxAttempts(),
                d.retryDelays(), d.retryBudgetRatio(), d.retryBudgetWindow(), d.retryBudgetMinRetries(),
                d.urgentShare());
    }

    private final SettingsHolder settings = new SettingsHolder(withConcurrency(3));
    private final ConcurrencyLimiter limiter = new ConcurrencyLimiter(settings);

    private Permit acquire(Priority priority) {
        return limiter.tryAcquire(priority).orElseThrow();
    }

    @Test
    void grantsPermitsUpToTheLimitAndNoMore() {
        acquire(Priority.URGENT);
        acquire(Priority.URGENT);
        acquire(Priority.INITIAL_LOAD);
        assertThat(limiter.tryAcquire(Priority.URGENT)).isEmpty();
        assertThat(limiter.inFlight()).isEqualTo(3);
    }

    @Test
    void releaseFreesASlot() {
        var permit = acquire(Priority.URGENT);
        acquire(Priority.URGENT);
        acquire(Priority.URGENT);
        limiter.release(permit, Response.NEUTRAL);
        assertThat(limiter.inFlight()).isEqualTo(2);
        assertThat(limiter.tryAcquire(Priority.URGENT)).isPresent();
    }

    @Test
    void countsInFlightPerPriority() {
        acquire(Priority.URGENT);
        var initial = acquire(Priority.INITIAL_LOAD);
        assertThat(limiter.inFlight(Priority.URGENT)).isEqualTo(1);
        assertThat(limiter.inFlight(Priority.INITIAL_LOAD)).isEqualTo(1);
        limiter.release(initial, Response.NORMAL);
        assertThat(limiter.inFlight(Priority.INITIAL_LOAD)).isZero();
        assertThat(limiter.inFlight(Priority.URGENT)).isEqualTo(1);
    }

    @Test
    void currentLimitIsTheConfiguredOneUntilADrop() {
        assertThat(limiter.currentLimit()).isEqualTo(3);
    }

    @Test
    void overloadedResponseLowersTheLimitOnceForPermitsOfTheSameGeneration() {
        var settings = new SettingsHolder(withConcurrency(4));
        var limiter = new ConcurrencyLimiter(settings);
        List<Permit> permits = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            permits.add(limiter.tryAcquire(Priority.URGENT).orElseThrow());
        }
        assertThat(limiter.release(permits.get(0), Response.OVERLOADED)).isTrue();
        assertThat(limiter.release(permits.get(1), Response.OVERLOADED)).isFalse();
        assertThat(limiter.release(permits.get(2), Response.OVERLOADED)).isFalse();
        assertThat(limiter.currentLimit()).isEqualTo(2);
    }

    @Test
    void afterADropNoPermitIsGrantedUntilInFlightIsBelowTheNewLimit() {
        var settings = new SettingsHolder(withConcurrency(4));
        var limiter = new ConcurrencyLimiter(settings);
        List<Permit> permits = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            permits.add(limiter.tryAcquire(Priority.URGENT).orElseThrow());
        }
        limiter.release(permits.get(0), Response.OVERLOADED);
        assertThat(limiter.tryAcquire(Priority.URGENT)).isEmpty();
        limiter.release(permits.get(1), Response.NEUTRAL);
        assertThat(limiter.tryAcquire(Priority.URGENT)).isEmpty();
        limiter.release(permits.get(2), Response.NEUTRAL);
        assertThat(limiter.tryAcquire(Priority.URGENT)).isPresent();
    }

    @Test
    void normalResponsesRaiseTheLimitBackStepByStep() {
        var settings = new SettingsHolder(withConcurrency(4));
        var limiter = new ConcurrencyLimiter(settings);
        limiter.release(limiter.tryAcquire(Priority.URGENT).orElseThrow(), Response.OVERLOADED);
        assertThat(limiter.currentLimit()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            limiter.release(limiter.tryAcquire(Priority.URGENT).orElseThrow(), Response.NORMAL);
        }
        assertThat(limiter.currentLimit()).isEqualTo(3);
    }

    @Test
    void neutralResponsesNeitherRaiseNorLower() {
        var settings = new SettingsHolder(withConcurrency(4));
        var limiter = new ConcurrencyLimiter(settings);
        limiter.release(limiter.tryAcquire(Priority.URGENT).orElseThrow(), Response.OVERLOADED);
        for (int i = 0; i < 20; i++) {
            limiter.release(limiter.tryAcquire(Priority.URGENT).orElseThrow(), Response.NEUTRAL);
        }
        assertThat(limiter.currentLimit()).isEqualTo(2);
    }

    @Test
    void reloadedConfiguredLimitStopsNewCallsAboveItWithoutCuttingRunningOnes() {
        var settings = new SettingsHolder(withConcurrency(4));
        var limiter = new ConcurrencyLimiter(settings);
        List<Permit> permits = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            permits.add(limiter.tryAcquire(Priority.URGENT).orElseThrow());
        }
        settings.replace(withConcurrency(2));
        assertThat(limiter.inFlight()).isEqualTo(4);
        assertThat(limiter.tryAcquire(Priority.URGENT)).isEmpty();
        limiter.release(permits.get(0), Response.NORMAL);
        limiter.release(permits.get(1), Response.NORMAL);
        assertThat(limiter.tryAcquire(Priority.URGENT)).isEmpty();
        limiter.release(permits.get(2), Response.NORMAL);
        assertThat(limiter.tryAcquire(Priority.URGENT)).isPresent();
        assertThat(limiter.tryAcquire(Priority.URGENT)).isEmpty();
    }

    @Test
    void reloadedHigherLimitIsUsedRightAway() {
        acquire(Priority.URGENT);
        acquire(Priority.URGENT);
        acquire(Priority.URGENT);
        settings.replace(withConcurrency(5));
        assertThat(limiter.tryAcquire(Priority.URGENT)).isPresent();
    }

    @Test
    void awaitReleaseReturnsWhenAnotherThreadReleases() throws Exception {
        var permit = acquire(Priority.URGENT);
        var started = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Boolean> waited = pool.submit(() -> {
                started.countDown();
                return limiter.awaitChange(Duration.ofSeconds(10));
            });
            started.await();
            TimeUnit.MILLISECONDS.sleep(50);
            limiter.release(permit, Response.NEUTRAL);
            assertThat(waited.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void awaitChangeTimesOutWhenNothingChanges() throws Exception {
        assertThat(limiter.awaitChange(Duration.ofMillis(20))).isFalse();
    }

    @Test
    void inFlightNeverExceedsTheLimitUnderContention() throws Exception {
        int limit = 5;
        var settings = new SettingsHolder(withConcurrency(limit));
        var limiter = new ConcurrencyLimiter(settings);
        var current = new AtomicInteger();
        var max = new AtomicInteger();
        var violations = new AtomicInteger();
        var start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int t = 0; t < 16; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < 2_000; n++) {
                        Optional<Permit> permit = limiter.tryAcquire(Priority.URGENT);
                        if (permit.isPresent()) {
                            int now = current.incrementAndGet();
                            max.accumulateAndGet(now, Math::max);
                            if (now > limit) {
                                violations.incrementAndGet();
                            }
                            current.decrementAndGet();
                            limiter.release(permit.get(), n % 50 == 0 ? Response.OVERLOADED : Response.NORMAL);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(violations).hasValue(0);
        assertThat(max.get()).isLessThanOrEqualTo(limit);
        assertThat(limiter.inFlight()).isZero();
    }
}
