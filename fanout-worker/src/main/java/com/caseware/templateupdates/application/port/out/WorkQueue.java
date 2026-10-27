package com.caseware.templateupdates.application.port.out;

import com.caseware.templateupdates.application.Lease;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.WorkItem;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public interface WorkQueue {

    void enqueue(EngagementRef engagement, Reason reason);

    Optional<Lease> claim(Priority priority, Instant now, Duration leaseDuration);

    boolean hasReady(Priority priority, Instant now);

    boolean ack(Lease lease);

    boolean retryLater(Lease lease, WorkItem updated);

    boolean remove(Lease lease, Set<Reason> reasons);

    long countPending(Reason job, Instant now);

    long countLeased(Reason job, Instant now);
}
