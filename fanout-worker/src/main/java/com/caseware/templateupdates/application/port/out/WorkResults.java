package com.caseware.templateupdates.application.port.out;

import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.Result;
import java.util.List;
import java.util.Set;

public interface WorkResults {

    Set<Reason> completedReasons(EngagementId id, Set<Reason> candidates);

    void recordCompleted(EngagementId id, Set<Reason> reasons, String version, int attempts);

    void recordFailed(EngagementId id, Set<Reason> reasons, String error, int attempts);

    long countCompleted(Reason job);

    long countFailed(Reason job);

    List<Result.Failed> failed(Reason job);
}
