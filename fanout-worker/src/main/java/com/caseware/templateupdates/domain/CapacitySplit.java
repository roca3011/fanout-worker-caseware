package com.caseware.templateupdates.domain;

import java.util.Optional;

/** Weighted split of the concurrency limit that never wastes free capacity (research R2). */
public final class CapacitySplit {

    private CapacitySplit() {}

    /**
     * Priority that should receive the next free slot, given what is in flight and what is ready.
     * Calls already running are never cut: a priority over its quota just stops receiving slots.
     */
    public static Optional<Priority> next(
            int limit,
            double urgentShare,
            int urgentInFlight,
            int initialInFlight,
            boolean urgentReady,
            boolean initialReady) {
        if (!urgentReady && !initialReady) {
            return Optional.empty();
        }
        if (!initialReady) {
            return Optional.of(Priority.URGENT);
        }
        if (!urgentReady) {
            return Optional.of(Priority.INITIAL_LOAD);
        }
        int urgentQuota = urgentShare == 0 ? 0 : Math.max(1, (int) Math.round(limit * urgentShare));
        if (urgentInFlight < urgentQuota) {
            return Optional.of(Priority.URGENT);
        }
        if (initialInFlight < limit - urgentQuota) {
            return Optional.of(Priority.INITIAL_LOAD);
        }
        return Optional.of(Priority.URGENT);
    }
}
