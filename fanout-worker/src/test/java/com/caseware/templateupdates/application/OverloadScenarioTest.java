package com.caseware.templateupdates.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.application.port.out.AlertType;
import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.InitialLoad;
import com.caseware.templateupdates.domain.LoadOutcome;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.fixtures.ProcessorHarness;
import com.caseware.templateupdates.fixtures.ProcessorHarness.Work;
import com.caseware.templateupdates.fixtures.TestSettings;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Quickstart scenario 2: a burst of "overloaded" answers lowers the limit once, then it climbs back. */
class OverloadScenarioTest {

    private static final Reason INITIAL = new InitialLoad();
    private static final int TOTAL = 1_600;

    private final ProcessorHarness h = new ProcessorHarness(TestSettings.withConcurrency(50));

    private List<Work> takeUpTo(int count) {
        List<Work> works = new ArrayList<>();
        while (works.size() < count && h.queue.hasReady(Priority.INITIAL_LOAD, h.clock.instant())) {
            works.add(h.take(Priority.INITIAL_LOAD));
        }
        return works;
    }

    @Test
    void fiftyOverloadedAnswersLowerTheLimitOnceThenItClimbsBackToFiftyWithNoEngagementFailed() {
        for (int i = 0; i < TOTAL; i++) {
            h.queue.enqueue(ProcessorHarness.ref("E" + i), INITIAL);
        }
        for (int i = 0; i < 50; i++) {
            h.system.script(new EngagementId("E" + i), new LoadOutcome.Overloaded(),
                    new LoadOutcome.Loaded("v1", 1));
        }

        var burst = takeUpTo(50);
        assertThat(burst).hasSize(50);
        burst.forEach(h::process);

        assertThat(h.limiter.currentLimit()).isEqualTo(25);
        assertThat(h.alerts.count(AlertType.OVERLOADED)).isEqualTo(1);
        assertThat(h.results.countFailed(INITIAL)).isZero();

        var firstBatch = takeUpTo(25);
        assertThat(firstBatch).hasSize(25);
        assertThat(h.limiter.tryAcquire(Priority.INITIAL_LOAD)).isEmpty();
        firstBatch.forEach(h::process);
        assertThat(h.limiter.currentLimit()).isEqualTo(26);

        while (h.limiter.currentLimit() < 50) {
            var batch = takeUpTo(h.limiter.currentLimit());
            assertThat(batch).isNotEmpty();
            batch.forEach(h::process);
        }

        assertThat(h.limiter.currentLimit()).isEqualTo(50);
        assertThat(h.alerts.count(AlertType.OVERLOADED)).isEqualTo(1);
        assertThat(h.results.countFailed(INITIAL)).isZero();
    }
}
