package com.caseware.templateupdates.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.domain.RetryBudget.Verdict;
import com.caseware.templateupdates.fixtures.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryBudgetTest {

    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final double RATIO = 0.10;
    private static final int MIN_RETRIES = 5;

    private final MutableClock clock = new MutableClock();
    private final RetryBudget budget = new RetryBudget(clock);

    private Verdict retry() {
        return budget.requestRetry(WINDOW, RATIO, MIN_RETRIES);
    }

    private void calls(int count) {
        for (int i = 0; i < count; i++) {
            budget.recordCall(WINDOW);
        }
    }

    @Test
    void aSingleFailedCallIsRetriedWithoutAlert() {
        calls(1);
        assertThat(retry()).isEqualTo(Verdict.ALLOWED);
    }

    @Test
    void withFewCallsFiveRetriesAreAllowedAndTheSixthIsDeferred() {
        calls(10);
        for (int i = 0; i < 5; i++) {
            assertThat(retry()).isEqualTo(Verdict.ALLOWED);
        }
        assertThat(retry()).isEqualTo(Verdict.EXHAUSTED_FIRST);
    }

    @Test
    void withManyCallsTheLimitIsTenPercentOfThem() {
        calls(250);
        for (int i = 0; i < 25; i++) {
            assertThat(retry()).isEqualTo(Verdict.ALLOWED);
        }
        assertThat(retry()).isEqualTo(Verdict.EXHAUSTED_FIRST);
    }

    @Test
    void justUnderTheTenPercentThresholdTheFloorStillApplies() {
        calls(249);
        for (int i = 0; i < 24; i++) {
            assertThat(retry()).isEqualTo(Verdict.ALLOWED);
        }
        assertThat(retry()).isEqualTo(Verdict.EXHAUSTED_FIRST);
    }

    @Test
    void onlyTheFirstDeferralOfAnExhaustionReportsIt() {
        calls(10);
        for (int i = 0; i < 5; i++) {
            retry();
        }
        assertThat(retry()).isEqualTo(Verdict.EXHAUSTED_FIRST);
        assertThat(retry()).isEqualTo(Verdict.EXHAUSTED);
        assertThat(retry()).isEqualTo(Verdict.EXHAUSTED);
    }

    @Test
    void deferredRetriesAreNotCounted() {
        calls(10);
        for (int i = 0; i < 5; i++) {
            retry();
        }
        retry();
        retry();
        assertThat(budget.retriesInWindow(WINDOW)).isEqualTo(5);
    }

    @Test
    void aNewExhaustionAfterRecoveryAlertsAgain() {
        calls(10);
        for (int i = 0; i < 5; i++) {
            retry();
        }
        assertThat(retry()).isEqualTo(Verdict.EXHAUSTED_FIRST);
        clock.advance(WINDOW.plusSeconds(1));
        calls(10);
        for (int i = 0; i < 5; i++) {
            assertThat(retry()).isEqualTo(Verdict.ALLOWED);
        }
        assertThat(retry()).isEqualTo(Verdict.EXHAUSTED_FIRST);
    }

    @Test
    void eventsOlderThanTheWindowStopCounting() {
        calls(100);
        retry();
        clock.advance(WINDOW.minusSeconds(1));
        assertThat(budget.callsInWindow(WINDOW)).isEqualTo(100);
        assertThat(budget.retriesInWindow(WINDOW)).isEqualTo(1);
        clock.advance(Duration.ofSeconds(2));
        assertThat(budget.retriesInWindow(WINDOW)).isZero();
        assertThat(budget.callsInWindow(WINDOW)).isZero();
    }

    @Test
    void eventsExactlyAtTheWindowEdgeAreOut() {
        calls(1);
        clock.advance(WINDOW);
        assertThat(budget.callsInWindow(WINDOW)).isZero();
    }

    @Test
    void zeroMinimumAndFewCallsDefersAtOnce() {
        calls(5);
        assertThat(budget.requestRetry(WINDOW, RATIO, 0)).isEqualTo(Verdict.EXHAUSTED_FIRST);
    }

    @Test
    void concurrentCallersGetExactCountsAndASingleAlert() throws Exception {
        int threads = 16;
        int perThread = 200;
        var start = new CountDownLatch(1);
        var allowed = new AtomicInteger();
        var firsts = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < perThread; n++) {
                        budget.recordCall(WINDOW);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
        assertThat(budget.callsInWindow(WINDOW)).isEqualTo(threads * perThread);

        var other = new RetryBudget(clock);
        var start2 = new CountDownLatch(1);
        futures.clear();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start2.await();
                    for (int n = 0; n < perThread; n++) {
                        switch (other.requestRetry(WINDOW, 0.0001, MIN_RETRIES)) {
                            case ALLOWED -> allowed.incrementAndGet();
                            case EXHAUSTED_FIRST -> firsts.incrementAndGet();
                            case EXHAUSTED -> { }
                        }
                    }
                    return null;
                }));
            }
            start2.countDown();
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
        assertThat(allowed).hasValue(MIN_RETRIES);
        assertThat(firsts).hasValue(1);
        assertThat(other.retriesInWindow(WINDOW)).isEqualTo(MIN_RETRIES);
    }

    @Test
    void everyOperationReleasesItsLock() throws Exception {
        var thread = new Thread(() -> {
            budget.recordCall(WINDOW);
            budget.requestRetry(WINDOW, RATIO, MIN_RETRIES);
            budget.callsInWindow(WINDOW);
            budget.retriesInWindow(WINDOW);
        });
        thread.start();
        thread.join();

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            budget.recordCall(WINDOW);
            budget.callsInWindow(WINDOW);
            budget.retriesInWindow(WINDOW);
        });
    }
}
