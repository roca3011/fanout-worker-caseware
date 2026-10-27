package com.caseware.templateupdates.infrastructure.input;

import com.caseware.templateupdates.application.FanOutSettings;
import com.caseware.templateupdates.application.SettingsHolder;
import com.caseware.templateupdates.application.port.out.Alert;
import com.caseware.templateupdates.application.port.out.AlertSink;
import com.caseware.templateupdates.application.port.out.AlertType;
import java.util.function.Supplier;

public final class SettingsReloader {

    private final SettingsHolder holder;
    private final AlertSink alerts;

    public SettingsReloader(SettingsHolder holder, AlertSink alerts) {
        this.holder = holder;
        this.alerts = alerts;
    }

    /** Replaces the settings at once; if building the candidate fails, keeps the old ones and alerts. */
    public boolean reload(Supplier<FanOutSettings> candidate) {
        try {
            holder.replace(candidate.get());
            return true;
        } catch (RuntimeException e) {
            alerts.raise(new Alert(AlertType.SETTINGS_REJECTED, String.valueOf(e.getMessage())));
            return false;
        }
    }
}
