package com.caseware.templateupdates.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.FirmId;
import com.caseware.templateupdates.domain.InitialLoad;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.TemplateId;
import com.caseware.templateupdates.fixtures.MutableClock;
import com.caseware.templateupdates.fixtures.TestSettings;
import com.caseware.templateupdates.infrastructure.output.InMemoryWorkQueue;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Every public method must release its lock. A thread that dies holding a lock would block the
 * next caller forever, so each test runs the operations on one thread and probes from another.
 */
class LocksReleasedTest {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    private static void onAnotherThread(Runnable work) throws InterruptedException {
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        thread.join();
        assertThat(failure.get()).isNull();
    }

    private static void probe(Runnable work) {
        assertTimeoutPreemptively(PROBE_TIMEOUT, work::run);
    }

    @Test
    void theWorkQueueReleasesItsLockInEveryOperation() throws Exception {
        var clock = new MutableClock();
        var queue = new InMemoryWorkQueue(clock);
        var ref = new EngagementRef(new EngagementId("E1"), new FirmId("F1"), new TemplateId("T1"));
        var other = new EngagementRef(new EngagementId("E2"), new FirmId("F1"), new TemplateId("T1"));

        onAnotherThread(() -> {
            var reason = new InitialLoad();
            queue.enqueue(ref, reason);
            queue.enqueue(other, reason);
            queue.hasReady(Priority.INITIAL_LOAD, clock.instant());
            var first = queue.claim(Priority.INITIAL_LOAD, clock.instant(), Duration.ofMinutes(6)).orElseThrow();
            queue.countPending(reason, clock.instant());
            queue.countLeased(reason, clock.instant());
            queue.retryLater(first, first.item());
            var second = queue.claim(Priority.INITIAL_LOAD, clock.instant(), Duration.ofMinutes(6)).orElseThrow();
            queue.remove(second, Set.of(reason));
            var third = queue.claim(Priority.INITIAL_LOAD, clock.instant(), Duration.ofMinutes(6)).orElseThrow();
            queue.ack(third);
        });

        probe(() -> queue.enqueue(ref, new InitialLoad()));
        probe(() -> queue.countPending(new InitialLoad(), clock.instant()));
        probe(() -> queue.countLeased(new InitialLoad(), clock.instant()));
        probe(() -> queue.hasReady(Priority.INITIAL_LOAD, clock.instant()));
    }

    @Test
    void theLimiterReleasesItsLockInEveryOperation() throws Exception {
        var limiter = new ConcurrencyLimiter(new SettingsHolder(TestSettings.withConcurrency(5)));

        onAnotherThread(() -> {
            var permit = limiter.tryAcquire(Priority.URGENT).orElseThrow();
            limiter.inFlight();
            limiter.inFlight(Priority.URGENT);
            limiter.currentLimit();
            limiter.release(permit, ConcurrencyLimiter.Response.NORMAL);
            try {
                limiter.awaitChange(Duration.ofMillis(1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        probe(() -> limiter.tryAcquire(Priority.URGENT));
        probe(limiter::inFlight);
    }
}
