package com.caseware.templateupdates.domain;

public record JobProgress(Reason job, long pending, long inFlight, long completed, long failed) {}
