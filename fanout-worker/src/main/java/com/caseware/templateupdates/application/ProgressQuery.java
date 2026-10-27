package com.caseware.templateupdates.application;

import com.caseware.templateupdates.application.port.out.WorkQueue;
import com.caseware.templateupdates.application.port.out.WorkResults;
import com.caseware.templateupdates.domain.JobProgress;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.Result;
import java.time.Clock;
import java.util.List;

public final class ProgressQuery {

    private final WorkQueue queue;
    private final WorkResults results;
    private final Clock clock;

    public ProgressQuery(WorkQueue queue, WorkResults results, Clock clock) {
        this.queue = queue;
        this.results = results;
        this.clock = clock;
    }

    public JobProgress progress(Reason job) {
        var now = clock.instant();
        return new JobProgress(
                job,
                queue.countPending(job, now),
                queue.countLeased(job, now),
                results.countCompleted(job),
                results.countFailed(job));
    }

    public List<Result.Failed> failed(Reason job) {
        return results.failed(job);
    }
}
