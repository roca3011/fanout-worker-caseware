package com.caseware.templateupdates.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.FirmId;
import com.caseware.templateupdates.domain.InitialLoad;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.TemplateId;
import com.caseware.templateupdates.fixtures.MutableClock;
import com.caseware.templateupdates.infrastructure.output.InMemoryWorkQueue;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnqueueServiceTest {

    private final MutableClock clock = new MutableClock();
    private final InMemoryWorkQueue queue = new InMemoryWorkQueue(clock);
    private final EnqueueService service = new EnqueueService(queue);

    private static EngagementRef ref(String id) {
        return new EngagementRef(new EngagementId(id), new FirmId("F1"), new TemplateId("T1"));
    }

    @Test
    void enqueuesEveryEngagementAsInitialLoad() {
        service.enqueueInitialLoad(List.of(ref("E1"), ref("E2"), ref("E3")));

        assertThat(queue.countPending(new InitialLoad(), clock.instant())).isEqualTo(3);
        var lease = queue.claim(Priority.INITIAL_LOAD, clock.instant(), Duration.ofMinutes(6)).orElseThrow();
        assertThat(lease.item().reasons()).containsExactly(new InitialLoad());
        assertThat(queue.hasReady(Priority.URGENT, clock.instant())).isFalse();
    }

    @Test
    void enqueuingTheSameEngagementTwiceKeepsASingleEntry() {
        service.enqueueInitialLoad(List.of(ref("E1")));
        service.enqueueInitialLoad(List.of(ref("E1")));
        assertThat(queue.countPending(new InitialLoad(), clock.instant())).isEqualTo(1);
    }

    @Test
    void anEmptyCollectionEnqueuesNothing() {
        service.enqueueInitialLoad(List.of());
        assertThat(queue.countPending(new InitialLoad(), clock.instant())).isZero();
    }
}
