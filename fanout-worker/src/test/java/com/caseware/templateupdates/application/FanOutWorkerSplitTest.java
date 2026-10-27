package com.caseware.templateupdates.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.FirmId;
import com.caseware.templateupdates.domain.InitialLoad;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.TemplateId;
import com.caseware.templateupdates.domain.TemplatePublished;
import com.caseware.templateupdates.fixtures.ProcessorHarness;
import com.caseware.templateupdates.fixtures.TestSettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FanOutWorkerSplitTest {

    private static final Reason INITIAL = new InitialLoad();
    private static final Reason PUBLISHED = new TemplatePublished(new TemplateId("T1"), "v2");

    private final List<Runnable> submitted = new ArrayList<>();

    private FanOutWorker workerFor(ProcessorHarness h) {
        return new FanOutWorker(h.queue, h.processor, h.limiter, h.settings, h.clock, submitted::add, null,
                Duration.ofMillis(10), Duration.ofSeconds(1));
    }

    private static void enqueue(ProcessorHarness h, String prefix, int count, Reason reason) {
        for (int i = 0; i < count; i++) {
            h.queue.enqueue(new EngagementRef(new EngagementId(prefix + i), new FirmId("F" + (i % 5)),
                    new TemplateId("T1")), reason);
        }
    }

    @ParameterizedTest
    @CsvSource({"50,40,10", "4,3,1", "3,2,1", "2,2,0", "1,1,0"})
    void withBothTypesWaitingTheLimitIsSharedByTheConfiguredProportion(int limit, int urgent, int initial) {
        var h = new ProcessorHarness(TestSettings.withConcurrency(limit));
        enqueue(h, "U", 100, PUBLISHED);
        enqueue(h, "I", 100, INITIAL);
        var worker = workerFor(h);

        for (int i = 0; i < limit; i++) {
            assertThat(worker.dispatchOnce()).isTrue();
        }

        assertThat(worker.dispatchOnce()).isFalse();
        assertThat(h.limiter.inFlight(Priority.URGENT)).isEqualTo(urgent);
        assertThat(h.limiter.inFlight(Priority.INITIAL_LOAD)).isEqualTo(initial);
    }

    @Test
    void aSingleTypeGetsTheWholeLimit() {
        var h = new ProcessorHarness(TestSettings.withConcurrency(50));
        enqueue(h, "I", 100, INITIAL);
        var worker = workerFor(h);

        for (int i = 0; i < 50; i++) {
            assertThat(worker.dispatchOnce()).isTrue();
        }

        assertThat(h.limiter.inFlight(Priority.INITIAL_LOAD)).isEqualTo(50);
        assertThat(worker.dispatchOnce()).isFalse();
    }

    @Test
    void urgentWorkArrivingWhileInitialLoadHoldsEverythingWaitsForAFreeSlotWithoutCuttingAnyCall() {
        var h = new ProcessorHarness(TestSettings.withConcurrency(10));
        enqueue(h, "I", 100, INITIAL);
        var worker = workerFor(h);
        for (int i = 0; i < 10; i++) {
            worker.dispatchOnce();
        }
        enqueue(h, "U", 100, PUBLISHED);

        assertThat(worker.dispatchOnce()).isFalse();
        assertThat(h.limiter.inFlight(Priority.INITIAL_LOAD)).isEqualTo(10);

        submitted.get(0).run();

        assertThat(worker.dispatchOnce()).isTrue();
        assertThat(h.limiter.inFlight(Priority.URGENT)).isEqualTo(1);
        assertThat(h.limiter.inFlight(Priority.INITIAL_LOAD)).isEqualTo(9);
    }

    @Test
    void aFreedSlotGoesBackToThePriorityBelowItsQuota() {
        var h = new ProcessorHarness(TestSettings.withConcurrency(10));
        enqueue(h, "U", 100, PUBLISHED);
        enqueue(h, "I", 100, INITIAL);
        var worker = workerFor(h);
        for (int i = 0; i < 10; i++) {
            worker.dispatchOnce();
        }
        assertThat(h.limiter.inFlight(Priority.URGENT)).isEqualTo(8);
        assertThat(h.limiter.inFlight(Priority.INITIAL_LOAD)).isEqualTo(2);
        submitted.stream().skip(8).findFirst().orElseThrow().run();

        assertThat(worker.dispatchOnce()).isTrue();

        assertThat(h.limiter.inFlight(Priority.URGENT)).isEqualTo(8);
        assertThat(h.limiter.inFlight(Priority.INITIAL_LOAD)).isEqualTo(2);
    }

    @Test
    void whenOnlyInitialLoadIsLeftAfterUrgentFinishesItUsesAllTheCapacity() {
        var h = new ProcessorHarness(TestSettings.withConcurrency(10));
        enqueue(h, "U", 3, PUBLISHED);
        enqueue(h, "I", 100, INITIAL);
        var worker = workerFor(h);
        for (int i = 0; i < 10; i++) {
            worker.dispatchOnce();
        }
        assertThat(h.limiter.inFlight(Priority.URGENT)).isEqualTo(3);
        submitted.forEach(Runnable::run);
        submitted.clear();

        for (int i = 0; i < 10; i++) {
            worker.dispatchOnce();
        }

        assertThat(h.limiter.inFlight(Priority.INITIAL_LOAD)).isEqualTo(10);
        assertThat(h.limiter.inFlight(Priority.URGENT)).isZero();
    }
}
