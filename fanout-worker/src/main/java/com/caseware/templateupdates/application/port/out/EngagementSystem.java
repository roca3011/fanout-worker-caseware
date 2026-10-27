package com.caseware.templateupdates.application.port.out;

import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.LoadOutcome;
import java.time.Duration;

public interface EngagementSystem {

    LoadOutcome load(EngagementRef engagement, Duration responseTimeout);
}
