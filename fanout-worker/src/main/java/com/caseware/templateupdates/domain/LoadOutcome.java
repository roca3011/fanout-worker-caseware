package com.caseware.templateupdates.domain;

public sealed interface LoadOutcome {
    record Loaded(String version, long seq) implements LoadOutcome {}

    record Transient(String reason) implements LoadOutcome {}

    record Permanent(String reason) implements LoadOutcome {}

    record Overloaded() implements LoadOutcome {}
}
