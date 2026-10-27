package com.caseware.templateupdates.domain;

import java.time.Duration;

public sealed interface RetryDecision {
    record Complete(String version, long seq) implements RetryDecision {}

    record RetryAfter(Duration delay) implements RetryDecision {}

    record Requeue() implements RetryDecision {}

    record Defer(Duration delay) implements RetryDecision {}

    record Fail(String reason) implements RetryDecision {}
}
