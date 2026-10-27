package com.caseware.templateupdates.infrastructure.input;

import com.caseware.templateupdates.application.PublishService;
import com.caseware.templateupdates.domain.TemplateId;
import java.util.Objects;

public final class TemplatePublishedListener {

    private final PublishService service;

    public TemplatePublishedListener(PublishService service) {
        this.service = service;
    }

    /** Validates the notice and enqueues the engagements still without annotation; returns how many. */
    public int onTemplatePublished(TemplateId template, String version) {
        Objects.requireNonNull(template, "template");
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        return service.enqueueAffected(template, version);
    }
}
