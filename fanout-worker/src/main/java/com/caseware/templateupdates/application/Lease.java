package com.caseware.templateupdates.application;

import com.caseware.templateupdates.domain.WorkItem;
import java.time.Instant;
import java.util.UUID;

public record Lease(WorkItem item, UUID token, Instant expiresAt) {}
