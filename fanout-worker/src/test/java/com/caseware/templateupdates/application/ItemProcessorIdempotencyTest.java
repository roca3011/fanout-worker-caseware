package com.caseware.templateupdates.application;

import static org.assertj.core.api.Assertions.assertThat;
import static com.caseware.templateupdates.fixtures.ProcessorHarness.ref;

import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.InitialLoad;
import com.caseware.templateupdates.domain.LoadOutcome;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.TemplateId;
import com.caseware.templateupdates.domain.TemplatePublished;
import com.caseware.templateupdates.fixtures.ProcessorHarness;
import com.caseware.templateupdates.fixtures.TestSettings;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ItemProcessorIdempotencyTest {

    private static final EngagementId E1 = new EngagementId("E1");
    private static final Reason INITIAL = new InitialLoad();
    private static final Reason PUBLISHED = new TemplatePublished(new TemplateId("T1"), "v2");

    private final ProcessorHarness h = new ProcessorHarness(TestSettings.withConcurrency(10));

    @Test
    void theSameOrderTwiceCallsTheSystemOnce() {
        h.process(h.enqueueAndTake("E1", INITIAL));
        h.process(h.enqueueAndTake("E1", INITIAL));

        assertThat(h.system.callsTo(E1)).isEqualTo(1);
        assertThat(h.results.countCompleted(INITIAL)).isEqualTo(1);
        assertThat(h.queue.countPending(INITIAL, h.clock.instant())).isZero();
        assertThat(h.queue.countLeased(INITIAL, h.clock.instant())).isZero();
        assertThat(h.limiter.inFlight()).isZero();
    }

    @Test
    void anEngagementWithTwoReasonsIsReadOnceAndBothAreCompleted() {
        h.queue.enqueue(ref("E1"), INITIAL);
        h.queue.enqueue(ref("E1"), PUBLISHED);

        h.process(h.take(Priority.URGENT));

        assertThat(h.system.callsTo(E1)).isEqualTo(1);
        assertThat(h.results.countCompleted(INITIAL)).isEqualTo(1);
        assertThat(h.results.countCompleted(PUBLISHED)).isEqualTo(1);
        assertThat(h.queue.countPending(INITIAL, h.clock.instant())).isZero();
    }

    @Test
    void onlyTheReasonsNotYetCompletedAreProcessedAndKeepTheirCount() {
        h.process(h.enqueueAndTake("E1", INITIAL));
        h.queue.enqueue(ref("E1"), INITIAL);
        h.queue.enqueue(ref("E1"), PUBLISHED);

        h.process(h.take(Priority.URGENT));

        assertThat(h.system.callsTo(E1)).isEqualTo(2);
        assertThat(h.results.countCompleted(PUBLISHED)).isEqualTo(1);
        assertThat(h.results.countCompleted(INITIAL)).isEqualTo(1);
        assertThat(h.queue.countPending(INITIAL, h.clock.instant())).isZero();
        assertThat(h.queue.countLeased(INITIAL, h.clock.instant())).isZero();
    }

    @Test
    void aReasonArrivingDuringTheReadCompletesOnlyThePhotoAndReturnsToPending() {
        h.system.onCall(engagement -> h.queue.enqueue(ref("E1"), PUBLISHED));
        h.queue.enqueue(ref("E1"), INITIAL);

        h.process(h.take(Priority.INITIAL_LOAD));

        assertThat(h.results.countCompleted(INITIAL)).isEqualTo(1);
        assertThat(h.results.countCompleted(PUBLISHED)).isZero();
        assertThat(h.queue.countPending(PUBLISHED, h.clock.instant())).isEqualTo(1);

        h.system.onCall(engagement -> { });
        h.process(h.take(Priority.URGENT));

        assertThat(h.system.callsTo(E1)).isEqualTo(2);
        assertThat(h.results.countCompleted(PUBLISHED)).isEqualTo(1);
        assertThat(h.queue.countPending(PUBLISHED, h.clock.instant())).isZero();
    }

    @Test
    void anOlderReadingDoesNotOverwriteANewerAnnotation() {
        h.index.annotate(E1, "v12", 12);
        h.system.script(E1, new LoadOutcome.Loaded("v11", 11));

        h.process(h.enqueueAndTake("E1", INITIAL));

        assertThat(h.index.annotation(E1).orElseThrow().version()).isEqualTo("v12");
        assertThat(h.results.countCompleted(INITIAL)).isEqualTo(1);
    }

    @Test
    void whenTheLeaseExpiredDuringTheReadNothingIsRepeatedAndTheResultStands() {
        h.system.onCall(engagement -> h.clock.advance(h.settings.current().leaseDuration()));

        h.process(h.enqueueAndTake("E1", INITIAL));

        assertThat(h.results.countCompleted(INITIAL)).isEqualTo(1);
        assertThat(h.system.callsTo(E1)).isEqualTo(1);
        assertThat(h.queue.countPending(INITIAL, h.clock.instant())).isEqualTo(1);

        h.system.onCall(engagement -> { });
        h.process(h.take(Priority.INITIAL_LOAD));
        assertThat(h.system.callsTo(E1)).isEqualTo(1);
        assertThat(h.queue.countPending(INITIAL, h.clock.instant())).isZero();
    }

    @Test
    void completedReasonsAreRemovedFromTheQueueEvenWhenNothingIsLeftToCall() {
        h.process(h.enqueueAndTake("E1", INITIAL));
        var again = h.enqueueAndTake("E1", INITIAL);

        h.process(again);

        assertThat(h.queue.hasReady(Priority.INITIAL_LOAD, h.clock.instant())).isFalse();
        assertThat(h.results.completedReasons(E1, Set.of(INITIAL))).containsExactly(INITIAL);
    }
}
