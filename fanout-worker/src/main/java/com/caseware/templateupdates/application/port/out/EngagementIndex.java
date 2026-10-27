package com.caseware.templateupdates.application.port.out;

import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.TemplateId;
import java.util.List;

public interface EngagementIndex {

    List<EngagementRef> unannotated(TemplateId template);

    void annotate(EngagementId id, String version, long seq);
}
