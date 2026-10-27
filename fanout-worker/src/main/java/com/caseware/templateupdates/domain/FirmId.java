package com.caseware.templateupdates.domain;

public record FirmId(String value) {
    public FirmId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("FirmId must not be blank");
        }
    }
}
