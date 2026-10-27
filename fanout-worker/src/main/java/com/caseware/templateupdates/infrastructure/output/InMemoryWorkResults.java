package com.caseware.templateupdates.infrastructure.output;

import com.caseware.templateupdates.application.port.out.WorkResults;
import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.Result;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safety: state lives in a {@link ConcurrentHashMap}; every write goes through
 * {@code compute}, which is atomic per key, so "completed wins" holds under races.
 */
public final class InMemoryWorkResults implements WorkResults {

    private record Key(EngagementId id, Reason reason) {}

    private final Map<Key, Result> results = new ConcurrentHashMap<>();

    @Override
    public Set<Reason> completedReasons(EngagementId id, Set<Reason> candidates) {
        return candidates.stream()
                .filter(reason -> results.get(new Key(id, reason)) instanceof Result.Completed)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void recordCompleted(EngagementId id, Set<Reason> reasons, String version, int attempts) {
        for (Reason reason : reasons) {
            results.compute(new Key(id, reason), (key, existing) ->
                    existing instanceof Result.Completed ? existing : new Result.Completed(id, reason, version, attempts));
        }
    }

    @Override
    public void recordFailed(EngagementId id, Set<Reason> reasons, String error, int attempts) {
        for (Reason reason : reasons) {
            results.compute(new Key(id, reason), (key, existing) ->
                    existing instanceof Result.Completed ? existing : new Result.Failed(id, reason, error, attempts));
        }
    }

    @Override
    public long countCompleted(Reason job) {
        return completed(job).size();
    }

    @Override
    public long countFailed(Reason job) {
        return failed(job).size();
    }

    @Override
    public List<Result.Failed> failed(Reason job) {
        return results.values().stream()
                .filter(Result.Failed.class::isInstance)
                .map(Result.Failed.class::cast)
                .filter(result -> result.reason().equals(job))
                .toList();
    }

    public List<Result.Completed> completed(Reason job) {
        return results.values().stream()
                .filter(Result.Completed.class::isInstance)
                .map(Result.Completed.class::cast)
                .filter(result -> result.reason().equals(job))
                .toList();
    }
}
