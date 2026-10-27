package com.caseware.templateupdates.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.application.port.out.AlertType;
import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.InitialLoad;
import com.caseware.templateupdates.domain.LoadOutcome;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.Result;
import com.caseware.templateupdates.fixtures.ProcessorHarness;
import com.caseware.templateupdates.fixtures.TestSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ItemProcessorRetryTest {

    private static final EngagementId E1 = new EngagementId("E1");
    private static final EngagementId E2 = new EngagementId("E2");
    private static final Reason INITIAL = new InitialLoad();

    private final ProcessorHarness h = new ProcessorHarness(TestSettings.withConcurrency(10));

    private boolean ready() {
        return h.queue.hasReady(Priority.INITIAL_LOAD, h.clock.instant());
    }

    @Test
    void aTransientFailureIsRetriedTwiceWithGrowingDelaysThenFails() {
        h.system.script(E1, new LoadOutcome.Transient("timeout"));

        h.process(h.enqueueAndTake("E1", INITIAL));
        assertThat(ready()).isFalse();
        h.clock.advance(Duration.ofSeconds(23));
        assertThat(ready()).isFalse();
        h.clock.advance(Duration.ofSeconds(13));
        assertThat(ready()).isTrue();

        h.process(h.take(Priority.INITIAL_LOAD));
        h.clock.advance(Duration.ofSeconds(95));
        assertThat(ready()).isFalse();
        h.clock.advance(Duration.ofSeconds(49));
        assertThat(ready()).isTrue();

        assertThat(h.results.countFailed(INITIAL)).isZero();
        h.process(h.take(Priority.INITIAL_LOAD));

        assertThat(h.system.callsTo(E1)).isEqualTo(3);
        assertThat(h.results.failed(INITIAL)).containsExactly(new Result.Failed(E1, INITIAL, "timeout", 3));
        assertThat(h.queue.countPending(INITIAL, h.clock.instant())).isZero();
        assertThat(h.limiter.inFlight()).isZero();
    }

    @Test
    void aTransientFailureFollowedBySuccessCompletesWithTheAttemptCount() {
        h.system.script(E1, new LoadOutcome.Transient("timeout"), new LoadOutcome.Loaded("v1", 1));

        h.process(h.enqueueAndTake("E1", INITIAL));
        h.clock.advance(Duration.ofSeconds(36));
        h.process(h.take(Priority.INITIAL_LOAD));

        assertThat(h.results.completed(INITIAL)).containsExactly(new Result.Completed(E1, INITIAL, "v1", 2));
        assertThat(h.results.countFailed(INITIAL)).isZero();
    }

    @Test
    void aPermanentFailureIsNeverRetriedAndOthersKeepGoing() {
        h.system.script(E2, new LoadOutcome.Permanent("not-found"));

        h.process(h.enqueueAndTake("E2", INITIAL));
        h.process(h.enqueueAndTake("E1", INITIAL));

        assertThat(h.system.callsTo(E2)).isEqualTo(1);
        assertThat(h.results.failed(INITIAL)).containsExactly(new Result.Failed(E2, INITIAL, "not-found", 1));
        assertThat(h.results.countCompleted(INITIAL)).isEqualTo(1);
    }

    @Test
    void aSingleFailedCallIsRetriedWithoutAnyAlert() {
        h.system.script(E1, new LoadOutcome.Transient("timeout"));

        h.process(h.enqueueAndTake("E1", INITIAL));

        assertThat(h.alerts.count(AlertType.RETRY_BUDGET_EXHAUSTED)).isZero();
        assertThat(h.queue.countPending(INITIAL, h.clock.instant())).isEqualTo(1);
    }

    private void exhaustTheBudgetWithOverloads() {
        h.system.script(E2, new LoadOutcome.Overloaded());
        for (int i = 0; i < 5; i++) {
            h.process(h.enqueueAndTake("E2", INITIAL));
            assertThat(ready()).isTrue();
        }
    }

    @Test
    void whenTheBudgetIsExhaustedTheEntryIsSetAsideForTheWindowWithoutSpendingAnAttemptAndAlertsOnce() {
        exhaustTheBudgetWithOverloads();
        assertThat(h.alerts.count(AlertType.RETRY_BUDGET_EXHAUSTED)).isZero();

        h.process(h.take(Priority.INITIAL_LOAD));

        assertThat(ready()).isFalse();
        assertThat(h.alerts.count(AlertType.RETRY_BUDGET_EXHAUSTED)).isEqualTo(1);
        h.clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        assertThat(ready()).isFalse();
        h.clock.advance(Duration.ofSeconds(1));
        assertThat(ready()).isTrue();
        assertThat(h.take(Priority.INITIAL_LOAD).lease().item().attempts()).isZero();
    }

    @Test
    void furtherDeferralsInTheSameExhaustionDoNotAlertAgain() {
        exhaustTheBudgetWithOverloads();
        h.process(h.take(Priority.INITIAL_LOAD));

        h.system.script(E1, new LoadOutcome.Transient("timeout"));
        h.process(h.enqueueAndTake("E1", INITIAL));

        assertThat(h.alerts.count(AlertType.RETRY_BUDGET_EXHAUSTED)).isEqualTo(1);
        assertThat(ready()).isFalse();
        h.clock.advance(Duration.ofMinutes(5));
        var again = h.take(Priority.INITIAL_LOAD);
        assertThat(again.lease().item().attempts()).isZero();
        assertThat(h.results.countFailed(INITIAL)).isZero();
    }

    @Test
    void aTransientFailureWithNoAttemptsLeftFailsInsteadOfConsultingTheBudget() {
        h.system.script(E1, new LoadOutcome.Transient("timeout"));
        h.process(h.enqueueAndTake("E1", INITIAL));
        h.clock.advance(Duration.ofSeconds(36));
        h.process(h.take(Priority.INITIAL_LOAD));
        h.system.script(E2, new LoadOutcome.Overloaded());
        for (int i = 0; i < 3; i++) {
            h.process(h.enqueueAndTake("E2", INITIAL));
        }
        h.clock.advance(Duration.ofSeconds(144));

        h.process(h.take(Priority.INITIAL_LOAD));

        assertThat(h.results.failed(INITIAL)).containsExactly(new Result.Failed(E1, INITIAL, "timeout", 3));
        assertThat(h.alerts.count(AlertType.RETRY_BUDGET_EXHAUSTED)).isZero();
    }

    @Test
    void everyCallCountsTowardTheBudgetSoManyCallsAllowTenPercentRetries() {
        for (int i = 0; i < 60; i++) {
            h.process(h.enqueueAndTake("L" + i, INITIAL));
        }
        h.system.script(E2, new LoadOutcome.Overloaded());
        for (int i = 0; i < 6; i++) {
            h.process(h.enqueueAndTake("E2", INITIAL));
        }
        assertThat(h.alerts.count(AlertType.RETRY_BUDGET_EXHAUSTED)).isZero();

        h.process(h.take(Priority.INITIAL_LOAD));

        assertThat(h.alerts.count(AlertType.RETRY_BUDGET_EXHAUSTED)).isEqualTo(1);
    }
}
