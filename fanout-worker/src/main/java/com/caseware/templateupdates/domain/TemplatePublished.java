package com.caseware.templateupdates.domain;

import java.util.Objects;

public record TemplatePublished(TemplateId template, String version) implements Reason {
    public TemplatePublished {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(version, "version");
    }

    @Override
    public Priority priority() {
        return Priority.URGENT;
    }
}
