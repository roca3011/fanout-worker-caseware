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
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryWorkQueueTest {

    private static final Duration LEASE = Duration.ofMinutes(6);
    private static final Reason PUBLISHED = new TemplatePublished(new TemplateId("T1"), "v2");
    private static final Reason INITIAL = new InitialLoad();

    private final MutableClock clock = new MutableClock();
    private final InMemoryWorkQueue queue = new InMemoryWorkQueue(clock);

    private static EngagementRef ref(String id) {
        return new EngagementRef(new EngagementId(id), new FirmId("F1"), new TemplateId("T1"));
    }

    private Lease claim(Priority priority) {
        return queue.claim(priority, clock.instant(), LEASE).orElseThrow();
    }

    @Test
    void claimOnEmptyQueueReturnsNothing() {
        assertThat(queue.claim(Priority.URGENT, clock.instant(), LEASE)).isEmpty();
        assertThat(queue.hasReady(Priority.URGENT, clock.instant())).isFalse();
    }

    @Test
    void claimsInFifoOrderWithinAPriority() {
        queue.enqueue(ref("E1"), INITIAL);
        queue.enqueue(ref("E2"), INITIAL);
        assertThat(claim(Priority.INITIAL_LOAD).item().engagement().id().value()).isEqualTo("E1");
        assertThat(claim(Priority.INITIAL_LOAD).item().engagement().id().value()).isEqualTo("E2");
    }

    @Test
    void claimOnlyReturnsEntriesOfTheRequestedPriority() {
        queue.enqueue(ref("E1"), INITIAL);
        assertThat(queue.claim(Priority.URGENT, clock.instant(), LEASE)).isEmpty();
        queue.enqueue(ref("E2"), PUBLISHED);
        assertThat(claim(Priority.URGENT).item().engagement().id().value()).isEqualTo("E2");
    }

    @Test
    void claimReservesUntilNowPlusLeaseAndHidesTheEntry() {
        queue.enqueue(ref("E1"), INITIAL);
        var lease = claim(Priority.INITIAL_LOAD);
        assertThat(lease.expiresAt()).isEqualTo(clock.instant().plus(LEASE));
        assertThat(queue.hasReady(Priority.INITIAL_LOAD, clock.instant())).isFalse();
        assertThat(queue.claim(Priority.INITIAL_LOAD, clock.instant(), LEASE)).isEmpty();
    }

    @Test
    void expiredLeaseMakesTheEntryReadyAgainWithANewToken() {
        queue.enqueue(ref("E1"), INITIAL);
        var first = claim(Priority.INITIAL_LOAD);
        clock.advance(LEASE);
        assertThat(queue.hasReady(Priority.INITIAL_LOAD, clock.instant())).isTrue();
        var second = claim(Priority.INITIAL_LOAD);
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(queue.ack(first)).isFalse();
        assertThat(queue.ack(second)).isTrue();
    }

    @Test
    void leaseJustBeforeExpiryIsStillValid() {
        queue.enqueue(ref("E1"), INITIAL);
        var lease = claim(Priority.INITIAL_LOAD);
        clock.advance(LEASE.minusNanos(1));
        assertThat(queue.ack(lease)).isTrue();
    }

    @Test
    void ackWithExpiredLeaseReturnsFalseAndKeepsTheEntry() {
        queue.enqueue(ref("E1"), INITIAL);
        var lease = claim(Priority.INITIAL_LOAD);
        clock.advance(LEASE);
        assertThat(queue.ack(lease)).isFalse();
        assertThat(queue.hasReady(Priority.INITIAL_LOAD, clock.instant())).isTrue();
    }

    @Test
    void mergesReasonsOfTheSameEngagementIntoOneEntry() {
        queue.enqueue(ref("E1"), INITIAL);
        queue.enqueue(ref("E1"), PUBLISHED);
        queue.enqueue(ref("E1"), PUBLISHED);
        assertThat(queue.claim(Priority.INITIAL_LOAD, clock.instant(), LEASE)).isEmpty();
        var lease = claim(Priority.URGENT);
        assertThat(lease.item().reasons()).containsExactlyInAnyOrder(INITIAL, PUBLISHED);
        assertThat(queue.countPending(INITIAL, clock.instant()) + queue.countLeased(INITIAL, clock.instant()))
                .isEqualTo(1);
    }

    @Test
    void ackRemovesTheEntry() {
        queue.enqueue(ref("E1"), INITIAL);
        var lease = claim(Priority.INITIAL_LOAD);
        assertThat(queue.ack(lease)).isTrue();
        assertThat(queue.countPending(INITIAL, clock.instant())).isZero();
        assertThat(queue.countLeased(INITIAL, clock.instant())).isZero();
        assertThat(queue.ack(lease)).isFalse();
    }

    @Test
    void reasonArrivingDuringTheLeaseSurvivesTheAckAndReturnsToPending() {
        queue.enqueue(ref("E1"), INITIAL);
        var lease = claim(Priority.INITIAL_LOAD);
        queue.enqueue(ref("E1"), PUBLISHED);
        assertThat(queue.ack(lease)).isTrue();
        var next = claim(Priority.URGENT);
        assertThat(next.item().reasons()).containsExactly(PUBLISHED);
        assertThat(next.item().attempts()).isZero();
    }

    @Test
    void pendingAndLeasedCountsFollowTheSnapshot() {
        queue.enqueue(ref("E1"), INITIAL);
        queue.enqueue(ref("E2"), INITIAL);
        var lease = claim(Priority.INITIAL_LOAD);
        queue.enqueue(ref("E1"), PUBLISHED);
        var now = clock.instant();
        assertThat(queue.countLeased(INITIAL, now)).isEqualTo(1);
        assertThat(queue.countPending(INITIAL, now)).isEqualTo(1);
        assertThat(queue.countLeased(PUBLISHED, now)).isZero();
        assertThat(queue.countPending(PUBLISHED, now)).isEqualTo(1);
        assertThat(lease.item().reasons()).containsExactly(INITIAL);
    }

    @Test
    void expiredLeasesCountAsPending() {
        queue.enqueue(ref("E1"), INITIAL);
        claim(Priority.INITIAL_LOAD);
        clock.advance(LEASE);
        assertThat(queue.countPending(INITIAL, clock.instant())).isEqualTo(1);
        assertThat(queue.countLeased(INITIAL, clock.instant())).isZero();
    }

    @Test
    void retryLaterHidesTheEntryUntilNotBeforeAndKeepsAttempts() {
        queue.enqueue(ref("E1"), INITIAL);
        var lease = claim(Priority.INITIAL_LOAD);
        var notBefore = clock.instant().plusSeconds(30);
        assertThat(queue.retryLater(lease, lease.item().nextAttempt(notBefore, "timeout"))).isTrue();
        assertThat(queue.hasReady(Priority.INITIAL_LOAD, clock.instant())).isFalse();
        assertThat(queue.countPending(INITIAL, clock.instant())).isEqualTo(1);
        clock.advance(Duration.ofSeconds(30));
        var again = claim(Priority.INITIAL_LOAD);
        assertThat(again.item().attempts()).isEqualTo(1);
        assertThat(again.item().lastError()).isEqualTo("timeout");
    }

    @Test
    void retryLaterKeepsReasonsThatArrivedDuringTheLease() {
        queue.enqueue(ref("E1"), INITIAL);
        var lease = claim(Priority.INITIAL_LOAD);
        queue.enqueue(ref("E1"), PUBLISHED);
        queue.retryLater(lease, lease.item().nextAttempt(clock.instant(), "x"));
        assertThat(claim(Priority.URGENT).item().reasons()).containsExactlyInAnyOrder(INITIAL, PUBLISHED);
    }

    @Test
    void retryLaterWithStaleTokenIsIgnored() {
        queue.enqueue(ref("E1"), INITIAL);
        var lease = claim(Priority.INITIAL_LOAD);
        clock.advance(LEASE);
        assertThat(queue.retryLater(lease, lease.item().nextAttempt(clock.instant(), "x"))).isFalse();
    }

    @Test
    void requeuedEntryGoesToTheEndOfTheLine() {
        queue.enqueue(ref("E1"), INITIAL);
        queue.enqueue(ref("E2"), INITIAL);
        var first = claim(Priority.INITIAL_LOAD);
        queue.retryLater(first, first.item());
        assertThat(claim(Priority.INITIAL_LOAD).item().engagement().id().value()).isEqualTo("E2");
        assertThat(claim(Priority.INITIAL_LOAD).item().engagement().id().value()).isEqualTo("E1");
    }

    @Test
    void removeDropsTheGivenReasonsAndEntryWhenNoneLeft() {
        queue.enqueue(ref("E1"), INITIAL);
        var lease = claim(Priority.INITIAL_LOAD);
        assertThat(queue.remove(lease, Set.of(INITIAL))).isTrue();
        assertThat(queue.countLeased(INITIAL, clock.instant())).isZero();
        assertThat(queue.countPending(INITIAL, clock.instant())).isZero();
    }

    @Test
    void removeOfSomeReasonsKeepsTheLeaseForTheRest() {
        queue.enqueue(ref("E1"), INITIAL);
        queue.enqueue(ref("E1"), PUBLISHED);
        var lease = claim(Priority.URGENT);
        assertThat(queue.remove(lease, Set.of(PUBLISHED))).isTrue();
        assertThat(queue.countLeased(INITIAL, clock.instant())).isEqualTo(1);
        assertThat(queue.countLeased(PUBLISHED, clock.instant())).isZero();
        assertThat(queue.ack(lease)).isTrue();
        assertThat(queue.countLeased(INITIAL, clock.instant())).isZero();
    }

    @Test
    void removeWithStaleTokenIsIgnored() {
        queue.enqueue(ref("E1"), INITIAL);
        var lease = claim(Priority.INITIAL_LOAD);
        clock.advance(LEASE);
        assertThat(queue.remove(lease, Set.of(INITIAL))).isFalse();
        assertThat(queue.countPending(INITIAL, clock.instant())).isEqualTo(1);
    }

    @Test
    void ackWithTokenOfAnotherEntryIsIgnored() {
        queue.enqueue(ref("E1"), INITIAL);
        var lease = claim(Priority.INITIAL_LOAD);
        var foreign = new Lease(lease.item(), java.util.UUID.randomUUID(), lease.expiresAt());
        assertThat(queue.ack(foreign)).isFalse();
        assertThat(queue.ack(lease)).isTrue();
    }

    @Test
    void entryWithPublishedReasonLeavesTheInitialLoadLineWhenPublishedArrives() {
        queue.enqueue(ref("E1"), INITIAL);
        queue.enqueue(ref("E1"), PUBLISHED);
        assertThat(queue.hasReady(Priority.INITIAL_LOAD, clock.instant())).isFalse();
        assertThat(queue.hasReady(Priority.URGENT, clock.instant())).isTrue();
    }

    @Test
    void ackOfUrgentReasonLeavesTheRemainingInitialLoadInItsOwnLine() {
        queue.enqueue(ref("E1"), PUBLISHED);
        var lease = claim(Priority.URGENT);
        queue.enqueue(ref("E1"), INITIAL);
        queue.ack(lease);
        assertThat(queue.hasReady(Priority.URGENT, clock.instant())).isFalse();
        assertThat(queue.hasReady(Priority.INITIAL_LOAD, clock.instant())).isTrue();
    }
}
