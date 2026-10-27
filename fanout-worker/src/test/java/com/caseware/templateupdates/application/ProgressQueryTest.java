package com.caseware.templateupdates.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.InitialLoad;
import com.caseware.templateupdates.domain.JobProgress;
import com.caseware.templateupdates.domain.LoadOutcome;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.Result;
import com.caseware.templateupdates.domain.TemplateId;
import com.caseware.templateupdates.domain.TemplatePublished;
import com.caseware.templateupdates.fixtures.ProcessorHarness;
import com.caseware.templateupdates.fixtures.ProcessorHarness.Work;
import com.caseware.templateupdates.fixtures.TestSettings;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProgressQueryTest {

    private static final Reason INITIAL = new InitialLoad();
    private static final Reason PUBLISHED = new TemplatePublished(new TemplateId("T1"), "v2");

    private final ProcessorHarness h = new ProcessorHarness(TestSettings.withConcurrency(10));
    private final ProgressQuery query = new ProgressQuery(h.queue, h.results, h.clock);

    private static long total(JobProgress p) {
        return p.pending() + p.inFlight() + p.completed() + p.failed();
    }

    @Test
    void anUnknownJobHasNoProgress() {
        assertThat(query.progress(INITIAL)).isEqualTo(new JobProgress(INITIAL, 0, 0, 0, 0));
    }

    @Test
    void midWayEveryEngagementIsInExactlyOneState() {
        for (int i = 0; i < 10; i++) {
            h.queue.enqueue(ProcessorHarness.ref("E" + i), INITIAL);
        }
        h.system.script(new EngagementId("E3"), new LoadOutcome.Permanent("gone"));
        List<Work> works = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            works.add(h.take(Priority.INITIAL_LOAD));
        }
        for (int i = 0; i < 4; i++) {
            h.process(works.get(i));
        }

        var progress = query.progress(INITIAL);

        assertThat(progress.completed()).isEqualTo(3);
        assertThat(progress.failed()).isEqualTo(1);
        assertThat(progress.inFlight()).isEqualTo(2);
        assertThat(progress.pending()).isEqualTo(4);
        assertThat(total(progress)).isEqualTo(10);
    }

    @Test
    void anExpiredLeaseCountsAsPendingNotInFlight() {
        h.queue.enqueue(ProcessorHarness.ref("E1"), INITIAL);
        h.take(Priority.INITIAL_LOAD);
        h.clock.advance(h.settings.current().leaseDuration());

        var progress = query.progress(INITIAL);

        assertThat(progress.pending()).isEqualTo(1);
        assertThat(progress.inFlight()).isZero();
    }

    @Test
    void aDeferredEntryCountsAsPending() {
        h.queue.enqueue(ProcessorHarness.ref("E1"), INITIAL);
        var work = h.take(Priority.INITIAL_LOAD);
        h.queue.retryLater(work.lease(), work.lease().item().deferredUntil(h.clock.instant().plusSeconds(300)));

        assertThat(query.progress(INITIAL).pending()).isEqualTo(1);
    }

    @Test
    void jobsAreTrackedSeparately() {
        h.queue.enqueue(ProcessorHarness.ref("E1"), INITIAL);
        h.queue.enqueue(ProcessorHarness.ref("E2"), PUBLISHED);
        h.process(h.take(Priority.URGENT));

        assertThat(query.progress(PUBLISHED).completed()).isEqualTo(1);
        assertThat(query.progress(PUBLISHED).pending()).isZero();
        assertThat(query.progress(INITIAL).completed()).isZero();
        assertThat(query.progress(INITIAL).pending()).isEqualTo(1);
    }

    @Test
    void failedListsTheErrorsOfTheJob() {
        h.system.script(new EngagementId("E1"), new LoadOutcome.Permanent("not-found"));
        h.queue.enqueue(ProcessorHarness.ref("E1"), INITIAL);
        h.process(h.take(Priority.INITIAL_LOAD));

        assertThat(query.failed(INITIAL))
                .containsExactly(new Result.Failed(new EngagementId("E1"), INITIAL, "not-found", 1));
        assertThat(query.failed(PUBLISHED)).isEmpty();
    }
}
