package com.caseware.templateupdates.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.FirmId;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.TemplateId;
import com.caseware.templateupdates.domain.TemplatePublished;
import com.caseware.templateupdates.fixtures.MutableClock;
import com.caseware.templateupdates.infrastructure.output.InMemoryEngagementIndex;
import com.caseware.templateupdates.infrastructure.output.InMemoryWorkQueue;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublishServiceTest {

    private static final TemplateId T1 = new TemplateId("T1");
    private static final TemplateId T2 = new TemplateId("T2");

    private final MutableClock clock = new MutableClock();
    private final InMemoryWorkQueue queue = new InMemoryWorkQueue(clock);
    private final InMemoryEngagementIndex index = new InMemoryEngagementIndex(List.of(
            ref("E1", T1), ref("E2", T1), ref("E3", T1), ref("E4", T2)));
    private final PublishService service = new PublishService(queue, index);

    private static EngagementRef ref(String id, TemplateId template) {
        return new EngagementRef(new EngagementId(id), new FirmId("F1"), template);
    }

    @Test
    void enqueuesOnlyUnannotatedEngagementsOfTheTemplateAsUrgent() {
        index.annotate(new EngagementId("E2"), "v2", 1);

        int enqueued = service.enqueueAffected(T1, "v2");

        var job = new TemplatePublished(T1, "v2");
        assertThat(enqueued).isEqualTo(2);
        assertThat(queue.countPending(job, clock.instant())).isEqualTo(2);
        var lease = queue.claim(Priority.URGENT, clock.instant(), Duration.ofMinutes(6)).orElseThrow();
        assertThat(lease.item().reasons()).containsExactly(job);
        assertThat(lease.item().engagement().id()).isNotEqualTo(new EngagementId("E2"));
    }

    @Test
    void aTemplateWithNothingPendingProducesAnEmptyJob() {
        for (String id : List.of("E1", "E2", "E3")) {
            index.annotate(new EngagementId(id), "v2", 1);
        }

        assertThat(service.enqueueAffected(T1, "v2")).isZero();

        assertThat(queue.hasReady(Priority.URGENT, clock.instant())).isFalse();
    }
}
