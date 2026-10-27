package com.caseware.templateupdates.domain;

import java.util.Objects;

public record EngagementRef(EngagementId id, FirmId firm, TemplateId template) {
    public EngagementRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(firm, "firm");
        Objects.requireNonNull(template, "template");
    }
}
