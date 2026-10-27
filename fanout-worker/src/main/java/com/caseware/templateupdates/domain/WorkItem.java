package com.caseware.templateupdates.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;

public record WorkItem(
        EngagementRef engagement, Set<Reason> reasons, int attempts, Instant notBefore, String lastError) {

    public WorkItem {
        Objects.requireNonNull(engagement, "engagement");
        Objects.requireNonNull(notBefore, "notBefore");
        reasons = Set.copyOf(reasons);
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("reasons must not be empty");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
    }

    public static WorkItem of(EngagementRef engagement, Reason reason, Instant notBefore) {
        return new WorkItem(engagement, Set.of(reason), 0, notBefore, null);
    }

    public Priority priority() {
        return reasons.stream().map(Reason::priority).min(Comparator.naturalOrder()).orElseThrow();
    }

    public WorkItem withReason(Reason reason) {
        var all = new java.util.HashSet<>(reasons);
        all.add(reason);
        return new WorkItem(engagement, all, attempts, notBefore, lastError);
    }

    public WorkItem withoutReasons(Set<Reason> removed) {
        var left = new java.util.HashSet<>(reasons);
        left.removeAll(removed);
        return new WorkItem(engagement, left, attempts, notBefore, lastError);
    }

    public WorkItem nextAttempt(Instant notBefore, String error) {
        return new WorkItem(engagement, reasons, attempts + 1, notBefore, error);
    }

    public WorkItem deferredUntil(Instant notBefore) {
        return new WorkItem(engagement, reasons, attempts, notBefore, lastError);
    }
}
