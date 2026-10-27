package com.caseware.templateupdates.domain;

public sealed interface Result {
    record Completed(EngagementId id, Reason reason, String version, int attempts) implements Result {}

    record Failed(EngagementId id, Reason reason, String error, int attempts) implements Result {}
}
