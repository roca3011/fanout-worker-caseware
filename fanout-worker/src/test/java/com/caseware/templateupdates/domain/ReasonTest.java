package com.caseware.templateupdates.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReasonTest {

    @Test
    void templatePublishedIsUrgent() {
        Reason reason = new TemplatePublished(new TemplateId("T1"), "v2");
        assertThat(reason.priority()).isEqualTo(Priority.URGENT);
    }

    @Test
    void initialLoadHasInitialLoadPriority() {
        assertThat(new InitialLoad().priority()).isEqualTo(Priority.INITIAL_LOAD);
    }

    @Test
    void publicationsOfDifferentVersionsAreDifferentReasons() {
        var t = new TemplateId("T1");
        assertThat(new TemplatePublished(t, "v1")).isNotEqualTo(new TemplatePublished(t, "v2"));
        assertThat(new InitialLoad()).isEqualTo(new InitialLoad());
    }
}
