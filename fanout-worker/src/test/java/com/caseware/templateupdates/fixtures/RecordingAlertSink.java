package com.caseware.templateupdates.fixtures;

import com.caseware.templateupdates.application.port.out.Alert;
import com.caseware.templateupdates.application.port.out.AlertSink;
import com.caseware.templateupdates.application.port.out.AlertType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RecordingAlertSink implements AlertSink {

    private final List<Alert> alerts = new CopyOnWriteArrayList<>();

    @Override
    public void raise(Alert alert) {
        alerts.add(alert);
    }

    public List<Alert> alerts() {
        return List.copyOf(alerts);
    }

    public long count(AlertType type) {
        return alerts.stream().filter(a -> a.type() == type).count();
    }
}
