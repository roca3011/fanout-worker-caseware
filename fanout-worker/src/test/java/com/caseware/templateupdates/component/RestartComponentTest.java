package com.caseware.templateupdates.component;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.application.EnqueueService;
import com.caseware.templateupdates.application.FanOutSettings;
import com.caseware.templateupdates.application.FanOutWorker;
import com.caseware.templateupdates.application.SettingsHolder;
import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.FirmId;
import com.caseware.templateupdates.domain.InitialLoad;
import com.caseware.templateupdates.domain.Result;
import com.caseware.templateupdates.domain.TemplateId;
import com.caseware.templateupdates.fixtures.Await;
import com.caseware.templateupdates.fixtures.MutableClock;
import com.caseware.templateupdates.fixtures.RecordingAlertSink;
import com.caseware.templateupdates.fixtures.ScriptedEngagementSystem;
import com.caseware.templateupdates.infrastructure.output.InMemoryEngagementIndex;
import com.caseware.templateupdates.infrastructure.output.InMemoryWorkQueue;
import com.caseware.templateupdates.infrastructure.output.InMemoryWorkResults;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("component")
class RestartComponentTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final InitialLoad INITIAL = new InitialLoad();
    private static final int TOTAL = 20_000;
    private static final int BEFORE_CRASH = 8_000;

    private final MutableClock clock = new MutableClock();
    private final SettingsHolder settings = new SettingsHolder(FanOutSettings.defaults());
    private final InMemoryWorkQueue queue = new InMemoryWorkQueue(clock);
    private final InMemoryWorkResults results = new InMemoryWorkResults();
    private final InMemoryEngagementIndex index = new InMemoryEngagementIndex(List.of());

    private FanOutWorker workerOver(ScriptedEngagementSystem system) {
        return FanOutWorker.create(queue, results, index, system, new RecordingAlertSink(), settings, clock,
                new Random(1));
    }

    @Test
    void aNewWorkerOverTheSameAdaptersRepeatsOnlyWhatWasInFlight() throws Exception {
        List<EngagementRef> refs = new ArrayList<>(TOTAL);
        for (int i = 0; i < TOTAL; i++) {
            refs.add(new EngagementRef(new EngagementId("E" + i), new FirmId("F" + (i % 20)), new TemplateId("T1")));
        }
        new EnqueueService(queue).enqueueInitialLoad(refs);

        var firstSystem = new ScriptedEngagementSystem();
        var first = workerOver(firstSystem);
        first.start();
        Await.until(TIMEOUT, () -> results.countCompleted(INITIAL) >= BEFORE_CRASH);
        firstSystem.holdCalls();
        assertThat(firstSystem.awaitInFlight(50, TIMEOUT)).isTrue();
        Thread.sleep(300);

        Set<EngagementId> alreadyCompleted = results.completed(INITIAL).stream()
                .map(Result.Completed::id)
                .collect(Collectors.toSet());
        var closing = Thread.ofVirtual().start(first::close);

        clock.advance(settings.current().leaseDuration().plusSeconds(1));
        var secondSystem = new ScriptedEngagementSystem();
        try (var second = workerOver(secondSystem)) {
            second.start();
            Await.until(TIMEOUT, () -> results.countCompleted(INITIAL) == TOTAL);

            for (EngagementId id : alreadyCompleted) {
                assertThat(secondSystem.callsTo(id)).isZero();
            }
            assertThat(secondSystem.totalCalls()).isEqualTo(TOTAL - alreadyCompleted.size());
            assertThat(alreadyCompleted.size()).isGreaterThanOrEqualTo(BEFORE_CRASH);
        } finally {
            firstSystem.releaseAll();
            closing.join();
        }

        assertThat(results.countCompleted(INITIAL)).isEqualTo(TOTAL);
        assertThat(results.countFailed(INITIAL)).isZero();
        assertThat(queue.countPending(INITIAL, clock.instant())).isZero();
        assertThat(queue.countLeased(INITIAL, clock.instant())).isZero();
    }
}
