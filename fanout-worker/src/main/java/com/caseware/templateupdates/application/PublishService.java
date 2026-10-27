package com.caseware.templateupdates.application;

import com.caseware.templateupdates.application.port.out.EngagementIndex;
import com.caseware.templateupdates.application.port.out.WorkQueue;
import com.caseware.templateupdates.domain.TemplateId;
import com.caseware.templateupdates.domain.TemplatePublished;

public final class PublishService {

    private final WorkQueue queue;
    private final EngagementIndex index;

    public PublishService(WorkQueue queue, EngagementIndex index) {
        this.queue = queue;
        this.index = index;
    }

    /** Enqueues as urgent the engagements still without annotation for this template; returns how many. */
    public int enqueueAffected(TemplateId template, String version) {
        var reason = new TemplatePublished(template, version);
        var pending = index.unannotated(template);
        pending.forEach(engagement -> queue.enqueue(engagement, reason));
        return pending.size();
    }
}
