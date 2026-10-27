package com.caseware.templateupdates.application.port.out;

public interface AlertSink {

    void raise(Alert alert);
}
