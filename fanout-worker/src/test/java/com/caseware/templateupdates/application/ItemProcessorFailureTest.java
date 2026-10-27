package com.caseware.templateupdates.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.application.port.out.WorkResults;
import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.InitialLoad;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.Result;
import com.caseware.templateupdates.domain.RetryBudget;
import com.caseware.templateupdates.domain.RetryPolicy;
import com.caseware.templateupdates.fixtures.ProcessorHarness;
import com.caseware.templateupdates.fixtures.TestSettings;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ItemProcessorFailureTest {

    private static final EngagementId E1 = new EngagementId("E1");
    private static final Reason INITIAL = new InitialLoad();

    private final ProcessorHarness h = new ProcessorHarness(TestSettings.withConcurrency(10));

    @Test
    void anUnexpectedExceptionFromTheSystemIsATransientFailureAndReleasesThePermit() {
        h.system.onCall(engagement -> {
            throw new IllegalStateException("boom");
        });

        h.process(h.enqueueAndTake("E1", INITIAL));

        assertThat(h.limiter.inFlight()).isZero();
        assertThat(h.results.countFailed(INITIAL)).isZero();
        assertThat(h.queue.countPending(INITIAL, h.clock.instant())).isEqualTo(1);
        assertThat(h.queue.hasReady(Priority.INITIAL_LOAD, h.clock.instant())).isFalse();
    }

    @Test
    void afterTheLastAttemptTheUnexpectedErrorIsRecordedWithItsKind() {
        h.system.onCall(engagement -> {
            throw new IllegalStateException("boom");
        });

        h.process(h.enqueueAndTake("E1", INITIAL));
        for (int i = 0; i < 2; i++) {
            h.clock.advance(Duration.ofMinutes(3));
            h.process(h.take(Priority.INITIAL_LOAD));
        }

        assertThat(h.results.failed(INITIAL)).hasSize(1);
        Result.Failed failed = h.results.failed(INITIAL).get(0);
        assertThat(failed.error()).isEqualTo("unexpected: IllegalStateException");
        assertThat(failed.attempts()).isEqualTo(3);
    }

    @Test
    void anExceptionAfterTheCallIsLoggedNotConfirmedAndTheEntryComesBackWhenTheLeaseExpires() {
        var failing = new ThrowingResults();
        var processor = new ItemProcessor(h.queue, failing, h.index, h.system, h.alerts, h.settings, h.limiter,
                h.clock, new RetryPolicy(new Random(1)), new RetryBudget(h.clock));
        var work = h.enqueueAndTake("E1", INITIAL);

        processor.process(work.lease(), work.permit());

        assertThat(h.limiter.inFlight()).isZero();
        assertThat(h.queue.countLeased(INITIAL, h.clock.instant())).isEqualTo(1);
        h.clock.advance(h.settings.current().leaseDuration());
        assertThat(h.queue.countPending(INITIAL, h.clock.instant())).isEqualTo(1);
        assertThat(h.queue.hasReady(Priority.INITIAL_LOAD, h.clock.instant())).isTrue();
    }

    @Test
    void anExceptionBeforeTheCallStillReleasesThePermit() {
        var failing = new ThrowingResults();
        failing.failOnLookup = true;
        var processor = new ItemProcessor(h.queue, failing, h.index, h.system, h.alerts, h.settings, h.limiter,
                h.clock, new RetryPolicy(new Random(1)), new RetryBudget(h.clock));
        var work = h.enqueueAndTake("E1", INITIAL);

        processor.process(work.lease(), work.permit());

        assertThat(h.limiter.inFlight()).isZero();
        assertThat(h.system.callsTo(E1)).isZero();
    }

    private static final class ThrowingResults implements WorkResults {
        boolean failOnLookup;

        @Override
        public Set<Reason> completedReasons(EngagementId id, Set<Reason> candidates) {
            if (failOnLookup) {
                throw new IllegalStateException("store down");
            }
            return Set.of();
        }

        @Override
        public void recordCompleted(EngagementId id, Set<Reason> reasons, String version, int attempts) {
            throw new IllegalStateException("store down");
        }

        @Override
        public void recordFailed(EngagementId id, Set<Reason> reasons, String error, int attempts) {
            throw new IllegalStateException("store down");
        }

        @Override
        public long countCompleted(Reason job) {
            return 0;
        }

        @Override
        public long countFailed(Reason job) {
            return 0;
        }

        @Override
        public List<Result.Failed> failed(Reason job) {
            return List.of();
        }
    }
}
