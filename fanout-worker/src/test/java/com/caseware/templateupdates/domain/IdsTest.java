package com.caseware.templateupdates.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void engagementIdRejectsBlankAndNull() {
        assertThatThrownBy(() -> new EngagementId("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngagementId("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngagementId(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new EngagementId("E1").value()).isEqualTo("E1");
    }

    @Test
    void firmIdRejectsBlankAndNull() {
        assertThatThrownBy(() -> new FirmId("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FirmId(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new FirmId("F1").value()).isEqualTo("F1");
    }

    @Test
    void templateIdRejectsBlankAndNull() {
        assertThatThrownBy(() -> new TemplateId(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemplateId(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new TemplateId("T1").value()).isEqualTo("T1");
    }

    @Test
    void engagementRefRejectsNulls() {
        var id = new EngagementId("E1");
        var firm = new FirmId("F1");
        var template = new TemplateId("T1");
        assertThatThrownBy(() -> new EngagementRef(null, firm, template)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EngagementRef(id, null, template)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EngagementRef(id, firm, null)).isInstanceOf(NullPointerException.class);
        assertThat(new EngagementRef(id, firm, template).firm()).isEqualTo(firm);
    }
}
