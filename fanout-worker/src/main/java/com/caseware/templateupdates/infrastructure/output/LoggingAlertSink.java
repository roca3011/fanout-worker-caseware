package com.caseware.templateupdates.infrastructure.output;

import com.caseware.templateupdates.application.port.out.Alert;
import com.caseware.templateupdates.application.port.out.AlertSink;

public final class LoggingAlertSink implements AlertSink {

    private final System.Logger logger;

    public LoggingAlertSink() {
        this(System.getLogger(LoggingAlertSink.class.getName()));
    }

    LoggingAlertSink(System.Logger logger) {
        this.logger = logger;
    }

    @Override
    public void raise(Alert alert) {
        logger.log(System.Logger.Level.WARNING, "alert type={0} detail={1}", alert.type(), alert.detail());
    }
}
