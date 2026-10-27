package com.caseware.templateupdates.fixtures;

import com.caseware.templateupdates.application.FanOutSettings;

public final class TestSettings {

    private TestSettings() {}

    public static FanOutSettings withConcurrency(int maxConcurrency) {
        var d = FanOutSettings.defaults();
        return new FanOutSettings(d.responseTimeout(), d.leaseDuration(), maxConcurrency, d.maxAttempts(),
                d.retryDelays(), d.retryBudgetRatio(), d.retryBudgetWindow(), d.retryBudgetMinRetries(),
                d.urgentShare());
    }
}
