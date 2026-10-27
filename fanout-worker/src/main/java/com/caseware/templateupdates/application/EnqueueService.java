package com.caseware.templateupdates.application;

import com.caseware.templateupdates.application.port.out.WorkQueue;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.InitialLoad;
import java.util.Collection;

public final class EnqueueService {

    private final WorkQueue queue;

    public EnqueueService(WorkQueue queue) {
        this.queue = queue;
    }

    public void enqueueInitialLoad(Collection<EngagementRef> engagements) {
        var reason = new InitialLoad();
        engagements.forEach(engagement -> queue.enqueue(engagement, reason));
    }
}
