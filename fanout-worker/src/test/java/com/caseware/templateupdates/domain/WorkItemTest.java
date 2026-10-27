package com.caseware.templateupdates.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkItemTest {

    private static final EngagementRef REF =
            new EngagementRef(new EngagementId("E1"), new FirmId("F1"), new TemplateId("T1"));
    private static final Reason PUBLISHED = new TemplatePublished(new TemplateId("T1"), "v2");
    private static final Reason INITIAL = new InitialLoad();
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void startsWithZeroAttemptsAndNoError() {
        var item = WorkItem.of(REF, INITIAL, T0);
        assertThat(item.attempts()).isZero();
        assertThat(item.lastError()).isNull();
        assertThat(item.notBefore()).isEqualTo(T0);
        assertThat(item.reasons()).containsExactly(INITIAL);
    }

    @Test
    void rejectsEmptyReasons() {
        assertThatThrownBy(() -> new WorkItem(REF, Set.of(), 0, T0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeAttempts() {
        assertThatThrownBy(() -> new WorkItem(REF, Set.of(INITIAL), -1, T0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reasonsAreDefensivelyCopied() {
        var mutable = new HashSet<Reason>(Set.of(INITIAL));
        var item = new WorkItem(REF, mutable, 0, T0, null);
        mutable.add(PUBLISHED);
        assertThat(item.reasons()).containsExactly(INITIAL);
        assertThatThrownBy(() -> item.reasons().add(PUBLISHED)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void priorityIsTheHighestOfItsReasons() {
        assertThat(WorkItem.of(REF, INITIAL, T0).priority()).isEqualTo(Priority.INITIAL_LOAD);
        assertThat(WorkItem.of(REF, PUBLISHED, T0).priority()).isEqualTo(Priority.URGENT);
        assertThat(WorkItem.of(REF, INITIAL, T0).withReason(PUBLISHED).priority()).isEqualTo(Priority.URGENT);
    }

    @Test
    void withReasonAccumulatesAndKeepsOtherFields() {
        var item = WorkItem.of(REF, INITIAL, T0).nextAttempt(T0.plusSeconds(30), "E");
        var merged = item.withReason(PUBLISHED);
        assertThat(merged.reasons()).containsExactlyInAnyOrder(INITIAL, PUBLISHED);
        assertThat(merged.attempts()).isEqualTo(1);
        assertThat(merged.lastError()).isEqualTo("E");
        assertThat(merged.notBefore()).isEqualTo(T0.plusSeconds(30));
    }

    @Test
    void withoutReasonsRemovesOnlyThoseGiven() {
        var item = WorkItem.of(REF, INITIAL, T0).withReason(PUBLISHED);
        assertThat(item.withoutReasons(Set.of(INITIAL)).reasons()).containsExactly(PUBLISHED);
    }

    @Test
    void nextAttemptSpendsOneAttempt() {
        var next = WorkItem.of(REF, INITIAL, T0).nextAttempt(T0.plusSeconds(30), "timeout");
        assertThat(next.attempts()).isEqualTo(1);
        assertThat(next.notBefore()).isEqualTo(T0.plusSeconds(30));
        assertThat(next.lastError()).isEqualTo("timeout");
    }

    @Test
    void deferredUntilDoesNotSpendAttemptsNorTouchError() {
        var item = WorkItem.of(REF, INITIAL, T0).nextAttempt(T0, "timeout");
        var deferred = item.deferredUntil(T0.plusSeconds(300));
        assertThat(deferred.attempts()).isEqualTo(1);
        assertThat(deferred.lastError()).isEqualTo("timeout");
        assertThat(deferred.notBefore()).isEqualTo(T0.plusSeconds(300));
    }
}
