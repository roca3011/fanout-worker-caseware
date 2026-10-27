package com.caseware.templateupdates.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.application.FanOutSettings;
import com.caseware.templateupdates.application.SettingsHolder;
import com.caseware.templateupdates.application.port.out.AlertType;
import com.caseware.templateupdates.fixtures.RecordingAlertSink;
import com.caseware.templateupdates.fixtures.TestSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SettingsReloaderTest {

    private final SettingsHolder holder = new SettingsHolder(FanOutSettings.defaults());
    private final RecordingAlertSink alerts = new RecordingAlertSink();
    private final SettingsReloader reloader = new SettingsReloader(holder, alerts);

    @Test
    void aValidCandidateReplacesTheSettingsAtOnce() {
        var candidate = TestSettings.withConcurrency(20);

        assertThat(reloader.reload(() -> candidate)).isTrue();

        assertThat(holder.current()).isSameAs(candidate);
        assertThat(alerts.alerts()).isEmpty();
    }

    @Test
    void anIncoherentCandidateIsRejectedTheOldSettingsStayAndAnAlertIsRaised() {
        var before = holder.current();

        boolean accepted = reloader.reload(() -> new FanOutSettings(
                Duration.ofMinutes(8), Duration.ofMinutes(6), 50, 3, before.retryDelays(), 0.1,
                Duration.ofMinutes(5), 5, 0.8));

        assertThat(accepted).isFalse();
        assertThat(holder.current()).isSameAs(before);
        assertThat(alerts.count(AlertType.SETTINGS_REJECTED)).isEqualTo(1);
        assertThat(alerts.alerts().get(0).detail()).contains("responseTimeout");
    }

    @Test
    void aSupplierThatFailsReadingIsAlsoRejected() {
        var before = holder.current();

        boolean accepted = reloader.reload(() -> {
            throw new IllegalStateException("parameter store unavailable");
        });

        assertThat(accepted).isFalse();
        assertThat(holder.current()).isSameAs(before);
        assertThat(alerts.count(AlertType.SETTINGS_REJECTED)).isEqualTo(1);
        assertThat(alerts.alerts().get(0).detail()).contains("parameter store unavailable");
    }

    @Test
    void aSupplierReturningNullIsRejected() {
        var before = holder.current();

        assertThat(reloader.reload(() -> null)).isFalse();

        assertThat(holder.current()).isSameAs(before);
        assertThat(alerts.count(AlertType.SETTINGS_REJECTED)).isEqualTo(1);
    }
}
