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
import com.caseware.templateupdates.domain.TemplateId;
import com.caseware.templateupdates.fixtures.Await;
import com.caseware.templateupdates.fixtures.RecordingAlertSink;
import com.caseware.templateupdates.fixtures.ScriptedEngagementSystem;
import com.caseware.templateupdates.fixtures.TestSettings;
import com.caseware.templateupdates.infrastructure.input.SettingsReloader;
import com.caseware.templateupdates.infrastructure.output.InMemoryEngagementIndex;
import com.caseware.templateupdates.infrastructure.output.InMemoryWorkQueue;
import com.caseware.templateupdates.infrastructure.output.InMemoryWorkResults;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("component")
class LimitComponentTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final InitialLoad INITIAL = new InitialLoad();

    private final Clock clock = Clock.systemUTC();
    private final SettingsHolder settings = new SettingsHolder(FanOutSettings.defaults());
    private final InMemoryWorkQueue queue = new InMemoryWorkQueue(clock);
    private final InMemoryWorkResults results = new InMemoryWorkResults();
    private final InMemoryEngagementIndex index = new InMemoryEngagementIndex(List.of());
    private final ScriptedEngagementSystem system = new ScriptedEngagementSystem();
    private final RecordingAlertSink alerts = new RecordingAlertSink();
    private final SettingsReloader reloader = new SettingsReloader(settings, alerts);
    private final FanOutWorker worker =
            FanOutWorker.create(queue, results, index, system, alerts, settings, clock, new java.util.Random(1));

    @AfterEach
    void tearDown() {
        system.releaseAll();
        worker.close();
    }

    private void enqueue(int count) {
        List<EngagementRef> refs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            refs.add(new EngagementRef(new EngagementId("E" + i), new FirmId("F" + (i % 20)), new TemplateId("T1")));
        }
        new EnqueueService(queue).enqueueInitialLoad(refs);
    }

    @Test
    void twentyThousandEngagementsNeverExceedFiftySimultaneousCalls() throws Exception {
        enqueue(20_000);
        system.holdCalls();
        worker.start();
        assertThat(system.awaitInFlight(50, TIMEOUT)).isTrue();

        Await.until(TIMEOUT, () -> {
            system.release(50);
            return results.countCompleted(INITIAL) == 20_000;
        });

        assertThat(system.maxInFlight()).isEqualTo(50);
        assertThat(results.countFailed(INITIAL)).isZero();
        assertThat(system.totalCalls()).isEqualTo(20_000);
    }

    @Test
    void loweringTheLimitStartsNoNewCallsAndNeverCutsRunningOnes() throws Exception {
        enqueue(200);
        system.holdCalls();
        worker.start();
        assertThat(system.awaitInFlight(50, TIMEOUT)).isTrue();

        assertThat(reloader.reload(() -> TestSettings.withConcurrency(20))).isTrue();
        Thread.sleep(300);
        assertThat(system.totalCalls()).isEqualTo(50);
        assertThat(system.inFlight()).isEqualTo(50);

        system.release(30);
        Await.until(TIMEOUT, () -> system.inFlight() == 20);
        Thread.sleep(300);
        assertThat(system.inFlight()).isEqualTo(20);
        assertThat(system.totalCalls()).isEqualTo(50);

        system.resetMaxInFlight();
        system.releaseAll();
        Await.until(TIMEOUT, () -> results.countCompleted(INITIAL) == 200);
        assertThat(system.maxInFlight()).isLessThanOrEqualTo(20);
    }
}
