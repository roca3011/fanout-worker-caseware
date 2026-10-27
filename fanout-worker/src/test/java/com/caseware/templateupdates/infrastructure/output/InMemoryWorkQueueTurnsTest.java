package com.caseware.templateupdates.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.application.Lease;
import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.FirmId;
import com.caseware.templateupdates.domain.InitialLoad;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.TemplateId;
import com.caseware.templateupdates.domain.TemplatePublished;
import com.caseware.templateupdates.fixtures.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryWorkQueueTurnsTest {

    private static final Duration LEASE = Duration.ofMinutes(6);
    private static final Reason INITIAL = new InitialLoad();
    private static final Reason PUBLISHED = new TemplatePublished(new TemplateId("T1"), "v2");

    private final MutableClock clock = new MutableClock();
    private final InMemoryWorkQueue queue = new InMemoryWorkQueue(clock);

    private void enqueue(String firm, String id, Reason reason) {
        queue.enqueue(new EngagementRef(new EngagementId(id), new FirmId(firm), new TemplateId("T1")), reason);
    }

    private Lease claim(Priority priority) {
        return queue.claim(priority, clock.instant(), LEASE).orElseThrow();
    }

    private static String firmOf(Lease lease) {
        return lease.item().engagement().firm().value();
    }

    @Test
    void aSmallFirmIsServedWithinTheFirstTurnsEvenBehindAHugeOne() {
        for (int i = 0; i < 40_000; i++) {
            enqueue("F1", "A" + i, INITIAL);
        }
        for (int i = 0; i < 40; i++) {
            enqueue("F2", "B" + i, INITIAL);
        }

        long fromSmallFirm = 0;
        for (int i = 0; i < 80; i++) {
            if (firmOf(claim(Priority.INITIAL_LOAD)).equals("F2")) {
                fromSmallFirm++;
            }
        }

        assertThat(fromSmallFirm).isEqualTo(40);
    }

    @Test
    void firmsTakeTurnsInOrder() {
        for (String firm : List.of("F1", "F2", "F3")) {
            for (int i = 0; i < 3; i++) {
                enqueue(firm, firm + "-" + i, INITIAL);
            }
        }

        List<String> order = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            order.add(firmOf(claim(Priority.INITIAL_LOAD)));
        }

        assertThat(order).containsExactly("F1", "F2", "F3", "F1", "F2", "F3", "F1", "F2", "F3");
    }

    @Test
    void withinAFirmEntriesKeepTheirArrivalOrder() {
        enqueue("F1", "a", INITIAL);
        enqueue("F1", "b", INITIAL);
        enqueue("F2", "c", INITIAL);

        assertThat(claim(Priority.INITIAL_LOAD).item().engagement().id().value()).isEqualTo("a");
        assertThat(claim(Priority.INITIAL_LOAD).item().engagement().id().value()).isEqualTo("c");
        assertThat(claim(Priority.INITIAL_LOAD).item().engagement().id().value()).isEqualTo("b");
    }

    @Test
    void aRequeuedEntryGoesToTheEndOfItsFirmQueueWithoutLosingTheFirmsTurn() {
        enqueue("F1", "a", INITIAL);
        enqueue("F1", "b", INITIAL);
        enqueue("F2", "c", INITIAL);

        var first = claim(Priority.INITIAL_LOAD);
        queue.retryLater(first, first.item());

        List<String> order = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            order.add(claim(Priority.INITIAL_LOAD).item().engagement().id().value());
        }

        assertThat(order).containsExactly("c", "b", "a");
    }

    @Test
    void aFirmWithNothingReadyIsSkippedWithoutWastingTheTurn() {
        enqueue("F1", "a", INITIAL);
        enqueue("F2", "b", INITIAL);
        enqueue("F2", "c", INITIAL);
        var leased = claim(Priority.INITIAL_LOAD);
        queue.retryLater(leased, leased.item().nextAttempt(clock.instant().plusSeconds(60), "x"));

        assertThat(firmOf(claim(Priority.INITIAL_LOAD))).isEqualTo("F2");
        assertThat(firmOf(claim(Priority.INITIAL_LOAD))).isEqualTo("F2");
        assertThat(queue.claim(Priority.INITIAL_LOAD, clock.instant(), LEASE)).isEmpty();
        assertThat(queue.hasReady(Priority.INITIAL_LOAD, clock.instant())).isFalse();
    }

    @Test
    void aFirmThatArrivesLaterGetsItsTurnRightAway() {
        enqueue("F1", "a", INITIAL);
        enqueue("F1", "b", INITIAL);
        enqueue("F1", "c", INITIAL);
        claim(Priority.INITIAL_LOAD);
        enqueue("F2", "d", INITIAL);

        assertThat(firmOf(claim(Priority.INITIAL_LOAD))).isEqualTo("F2");
    }

    @Test
    void eachPriorityRotatesItsOwnFirms() {
        enqueue("F1", "u1", PUBLISHED);
        enqueue("F2", "u2", PUBLISHED);
        enqueue("F1", "i1", INITIAL);
        enqueue("F2", "i2", INITIAL);

        assertThat(firmOf(claim(Priority.URGENT))).isEqualTo("F1");
        assertThat(firmOf(claim(Priority.INITIAL_LOAD))).isEqualTo("F1");
        assertThat(firmOf(claim(Priority.URGENT))).isEqualTo("F2");
        assertThat(firmOf(claim(Priority.INITIAL_LOAD))).isEqualTo("F2");
    }

    @Test
    void anEntryUpgradedToUrgentLeavesTheInitialLoadLineAndKeepsItsFirm() {
        enqueue("F1", "a", INITIAL);
        enqueue("F1", "a", PUBLISHED);

        assertThat(queue.hasReady(Priority.INITIAL_LOAD, clock.instant())).isFalse();
        assertThat(firmOf(claim(Priority.URGENT))).isEqualTo("F1");
    }

    @Test
    void removingAFirmBeforeTheCursorKeepsTheRotationOnTheNextFirm() {
        enqueue("F1", "a", INITIAL);
        enqueue("F2", "b1", INITIAL);
        enqueue("F2", "b2", INITIAL);
        enqueue("F3", "c", INITIAL);
        var fromF1 = claim(Priority.INITIAL_LOAD);
        claim(Priority.INITIAL_LOAD);

        queue.ack(fromF1);

        assertThat(claim(Priority.INITIAL_LOAD).item().engagement().id().value()).isEqualTo("c");
        assertThat(claim(Priority.INITIAL_LOAD).item().engagement().id().value()).isEqualTo("b2");
    }
}
