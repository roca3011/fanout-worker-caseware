package com.caseware.templateupdates.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutcomeTypesTest {

    @Test
    void outcomesCarryTheirData() {
        assertThat(new LoadOutcome.Loaded("v3", 12L).seq()).isEqualTo(12L);
        assertThat(new LoadOutcome.Loaded("v3", 12L).version()).isEqualTo("v3");
        assertThat(new LoadOutcome.Transient("timeout").reason()).isEqualTo("timeout");
        assertThat(new LoadOutcome.Permanent("gone").reason()).isEqualTo("gone");
        assertThat(new LoadOutcome.Overloaded()).isEqualTo(new LoadOutcome.Overloaded());
    }

    @Test
    void decisionsCarryTheirData() {
        assertThat(new RetryDecision.RetryAfter(Duration.ofSeconds(30)).delay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(new RetryDecision.Defer(Duration.ofMinutes(5)).delay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(new RetryDecision.Fail("x").reason()).isEqualTo("x");
        assertThat(new RetryDecision.Complete("v", 1L).version()).isEqualTo("v");
        assertThat(new RetryDecision.Requeue()).isEqualTo(new RetryDecision.Requeue());
    }

    @Test
    void resultsCarryTheirData() {
        var id = new EngagementId("E1");
        var completed = new Result.Completed(id, new InitialLoad(), "v1", 1);
        var failed = new Result.Failed(id, new InitialLoad(), "boom", 3);
        assertThat(completed.attempts()).isEqualTo(1);
        assertThat(failed.error()).isEqualTo("boom");
    }

    @Test
    void progressCarriesCounters() {
        var p = new JobProgress(new InitialLoad(), 1, 2, 3, 4);
        assertThat(p.pending()).isEqualTo(1);
        assertThat(p.inFlight()).isEqualTo(2);
        assertThat(p.completed()).isEqualTo(3);
        assertThat(p.failed()).isEqualTo(4);
    }
}
