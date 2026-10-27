package com.caseware.templateupdates.domain;

public record InitialLoad() implements Reason {
    @Override
    public Priority priority() {
        return Priority.INITIAL_LOAD;
    }
}
