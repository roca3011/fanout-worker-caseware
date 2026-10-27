package com.caseware.templateupdates.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.InitialLoad;
import com.caseware.templateupdates.domain.LoadOutcome;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.fixtures.CapturingLoggerFinder;
import com.caseware.templateupdates.fixtures.ProcessorHarness;
import com.caseware.templateupdates.fixtures.TestSettings;
import java.lang.System.Logger.Level;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Logs carry the engagement (constitution VIII); a lost lease is always visible. */
class ItemProcessorLogTest {

    private static final EngagementId E1 = new EngagementId("E1");
    private static final Reason INITIAL = new InitialLoad();

    private final ProcessorHarness h = new ProcessorHarness(TestSettings.withConcurrency(10));

    @BeforeEach
    void clearLogs() {
        CapturingLoggerFinder.clear();
    }

    @Test
    void aCompletedEngagementIsLoggedWithItsVersionAndNoLeaseWarning() {
        h.process(h.enqueueAndTake("E1", INITIAL));

        assertThat(CapturingLoggerFinder.has(Level.DEBUG, "completed engagement=E1 version=v1")).isTrue();
        assertThat(CapturingLoggerFinder.hasAny("lease lost")).isFalse();
    }

    @Test
    void aFailedEngagementIsLoggedWithItsError() {
        h.system.script(E1, new LoadOutcome.Permanent("not-found"));

        h.process(h.enqueueAndTake("E1", INITIAL));

        assertThat(CapturingLoggerFinder.has(Level.WARNING, "failed engagement=E1 error=not-found")).isTrue();
        assertThat(CapturingLoggerFinder.hasAny("lease lost")).isFalse();
    }

    @Test
    void aLeaseLostBeforeTheAckIsLogged() {
        h.system.onCall(engagement -> h.clock.advance(h.settings.current().leaseDuration()));

        h.process(h.enqueueAndTake("E1", INITIAL));

        assertThat(CapturingLoggerFinder.has(Level.WARNING, "lease lost on ack engagement=E1")).isTrue();
    }

    @Test
    void aLeaseLostBeforeTheRetryIsLogged() {
        h.system.script(E1, new LoadOutcome.Transient("timeout"));
        h.system.onCall(engagement -> h.clock.advance(h.settings.current().leaseDuration()));

        h.process(h.enqueueAndTake("E1", INITIAL));

        assertThat(CapturingLoggerFinder.has(Level.WARNING, "lease lost on retryLater engagement=E1")).isTrue();
    }

    @Test
    void aLeaseLostWhileDroppingCompletedReasonsIsLoggedAndNothingIsCalled() {
        h.queue.enqueue(ProcessorHarness.ref("E1"), INITIAL);
        var work = h.take(Priority.INITIAL_LOAD);
        h.results.recordCompleted(E1, Set.of(INITIAL), "v1", 1);
        h.clock.advance(h.settings.current().leaseDuration());

        h.process(work);

        assertThat(CapturingLoggerFinder.has(Level.WARNING, "lease lost on remove engagement=E1")).isTrue();
        assertThat(h.system.callsTo(E1)).isZero();
        assertThat(h.limiter.inFlight()).isZero();
    }

    @Test
    void anUnexpectedExceptionFromTheSystemIsLoggedWithItsCause() {
        var boom = new IllegalStateException("boom");
        h.system.onCall(engagement -> {
            throw boom;
        });

        h.process(h.enqueueAndTake("E1", INITIAL));

        var entry = CapturingLoggerFinder.find(Level.ERROR, "unexpected failure from engagement system engagement=E1");
        assertThat(entry.thrown()).isSameAs(boom);
    }
}
