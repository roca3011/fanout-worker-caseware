package com.caseware.templateupdates.domain;

public record EngagementId(String value) {
    public EngagementId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("EngagementId must not be blank");
        }
    }
}
