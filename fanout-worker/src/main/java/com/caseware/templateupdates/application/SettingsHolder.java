package com.caseware.templateupdates.application;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class SettingsHolder {

    private final AtomicReference<FanOutSettings> current;

    public SettingsHolder(FanOutSettings initial) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    public FanOutSettings current() {
        return current.get();
    }

    public void replace(FanOutSettings settings) {
        current.set(Objects.requireNonNull(settings, "settings"));
    }
}
