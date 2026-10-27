package com.caseware.templateupdates.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.application.ConcurrencyLimiter.Permit;
import com.caseware.templateupdates.application.port.out.AlertType;
import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.FirmId;
import com.caseware.templateupdates.domain.InitialLoad;
import com.caseware.templateupdates.domain.LoadOutcome;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.RetryBudget;
import com.caseware.templateupdates.domain.RetryPolicy;
import com.caseware.templateupdates.domain.Result;
import com.caseware.templateupdates.domain.TemplateId;
import com.caseware.templateupdates.fixtures.MutableClock;
import com.caseware.templateupdates.fixtures.RecordingAlertSink;
import com.caseware.templateupdates.fixtures.ScriptedEngagementSystem;
import com.caseware.templateupdates.fixtures.TestSettings;
import com.caseware.templateupdates.infrastructure.output.InMemoryEngagementIndex;
import com.caseware.templateupdates.infrastructure.output.InMemoryWorkQueue;
import com.caseware.templateupdates.infrastructure.output.InMemoryWorkResults;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ItemProcessorTest {

    private static final Reason INITIAL = new InitialLoad();
    private static final TemplateId TEMPLATE = new TemplateId("T1");

    private final MutableClock clock = new MutableClock();
    private final SettingsHolder settings = new SettingsHolder(TestSettings.withConcurrency(10));
    private final InMemoryWorkQueue queue = new InMemoryWorkQueue(clock);
    private final InMemoryWorkResults results = new InMemoryWorkResults();
    private final InMemoryEngagementIndex index = new InMemoryEngagementIndex(List.of());
    private final ScriptedEngagementSystem system = new ScriptedEngagementSystem();
    private final RecordingAlertSink alerts = new RecordingAlertSink();
    private final ConcurrencyLimiter limiter = new ConcurrencyLimiter(settings);
    private final ItemProcessor processor =
            new ItemProcessor(queue, results, index, system, alerts, settings, limiter, clock,
                    new RetryPolicy(new Random(1)), new RetryBudget(clock));

    private static EngagementRef ref(String id) {
        return new EngagementRef(new EngagementId(id), new FirmId("F1"), TEMPLATE);
    }

    private record Work(Lease lease, Permit permit) {}

    private Work take(String id) {
        queue.enqueue(ref(id), INITIAL);
        return takeNext();
    }

    private Work takeNext() {
        var permit = limiter.tryAcquire(Priority.INITIAL_LOAD).orElseThrow();
        var lease = queue.claim(Priority.INITIAL_LOAD, clock.instant(), settings.current().leaseDuration())
                .orElseThrow();
        return new Work(lease, permit);
    }

    @Test
    void loadedAnnotatesRecordsCompletedAndAcks() {
        var id = new EngagementId("E1");
        system.script(id, new LoadOutcome.Loaded("v7", 12));
        var work = take("E1");

        processor.process(work.lease(), work.permit());

        assertThat(index.annotation(id).orElseThrow()).isEqualTo(new InMemoryEngagementIndex.Annotation("v7", 12));
        assertThat(results.completed(INITIAL)).containsExactly(new Result.Completed(id, INITIAL, "v7", 1));
        assertThat(queue.countPending(INITIAL, clock.instant())).isZero();
        assertThat(queue.countLeased(INITIAL, clock.instant())).isZero();
        assertThat(system.callsTo(id)).isEqualTo(1);
        assertThat(limiter.inFlight()).isZero();
    }

    @Test
    void callsTheSystemWithTheCurrentResponseTimeout() {
        var work = take("E1");
        processor.process(work.lease(), work.permit());
        assertThat(system.lastTimeout()).isEqualTo(Duration.ofMinutes(3));

        settings.replace(new FanOutSettings(Duration.ofMinutes(1), Duration.ofMinutes(6), 10, 3,
                FanOutSettings.defaults().retryDelays(), 0.1, Duration.ofMinutes(5), 5, 0.8));
        var second = take("E2");
        processor.process(second.lease(), second.permit());
        assertThat(system.lastTimeout()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void overloadedGoesBackWithoutSpendingAnAttemptAndRaisesAnAlert() {
        var id = new EngagementId("E1");
        system.script(id, new LoadOutcome.Overloaded());
        var work = take("E1");

        processor.process(work.lease(), work.permit());

        assertThat(results.countFailed(INITIAL)).isZero();
        assertThat(results.countCompleted(INITIAL)).isZero();
        assertThat(queue.countPending(INITIAL, clock.instant())).isEqualTo(1);
        var again = takeNext();
        assertThat(again.lease().item().attempts()).isZero();
        assertThat(alerts.count(AlertType.OVERLOADED)).isEqualTo(1);
        assertThat(limiter.currentLimit()).isEqualTo(5);
    }

    @Test
    void manyOverloadsOfTheSameGenerationLowerOnceAndAlertOnce() {
        var works = new ArrayList<Work>();
        for (int i = 0; i < 10; i++) {
            var id = new EngagementId("E" + i);
            system.script(id, new LoadOutcome.Overloaded());
            queue.enqueue(ref("E" + i), INITIAL);
            works.add(takeNext());
        }

        works.forEach(w -> processor.process(w.lease(), w.permit()));

        assertThat(limiter.currentLimit()).isEqualTo(5);
        assertThat(alerts.count(AlertType.OVERLOADED)).isEqualTo(1);
        assertThat(results.countFailed(INITIAL)).isZero();
        assertThat(queue.countPending(INITIAL, clock.instant())).isEqualTo(10);
    }

    @Test
    void permanentFailureIsRecordedWithItsReasonAndLeavesTheQueue() {
        var id = new EngagementId("E1");
        system.script(id, new LoadOutcome.Permanent("not-found"));
        var work = take("E1");

        processor.process(work.lease(), work.permit());

        assertThat(results.failed(INITIAL)).containsExactly(new Result.Failed(id, INITIAL, "not-found", 1));
        assertThat(queue.countPending(INITIAL, clock.instant())).isZero();
        assertThat(system.callsTo(id)).isEqualTo(1);
    }

    @Test
    void transientFailureGoesBackForALaterAttempt() {
        var id = new EngagementId("E1");
        system.script(id, new LoadOutcome.Transient("timeout"));
        var work = take("E1");

        processor.process(work.lease(), work.permit());

        assertThat(results.countFailed(INITIAL)).isZero();
        assertThat(queue.countPending(INITIAL, clock.instant())).isEqualTo(1);
    }

    @Test
    void permitIsReleasedForEveryKindOfOutcome() {
        system.script(new EngagementId("E1"), new LoadOutcome.Transient("x"));
        system.script(new EngagementId("E2"), new LoadOutcome.Permanent("x"));
        system.script(new EngagementId("E3"), new LoadOutcome.Overloaded());
        for (String id : List.of("E1", "E2", "E3")) {
            var work = take(id);
            processor.process(work.lease(), work.permit());
        }
        assertThat(limiter.inFlight()).isZero();
    }
}
