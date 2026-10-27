package com.caseware.templateupdates.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.application.port.out.Alert;
import com.caseware.templateupdates.application.port.out.AlertType;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class LoggingAlertSinkTest {

    private record Entry(System.Logger.Level level, String message) {}

    private static final class CapturingLogger implements System.Logger {
        final List<Entry> entries = new ArrayList<>();

        @Override
        public String getName() {
            return "capture";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            entries.add(new Entry(level, msg));
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            entries.add(new Entry(level, params == null ? format : MessageFormat.format(format, params)));
        }
    }

    @Test
    void writesOneWarningPerAlertWithTypeAndDetail() {
        var logger = new CapturingLogger();
        var sink = new LoggingAlertSink(logger);

        sink.raise(new Alert(AlertType.RETRY_BUDGET_EXHAUSTED, "retry budget exhausted; deferring for PT5M"));
        sink.raise(new Alert(AlertType.OVERLOADED, "concurrency limit lowered to 25"));

        assertThat(logger.entries).hasSize(2);
        assertThat(logger.entries.get(0).level()).isEqualTo(System.Logger.Level.WARNING);
        assertThat(logger.entries.get(0).message())
                .contains("RETRY_BUDGET_EXHAUSTED")
                .contains("retry budget exhausted; deferring for PT5M");
        assertThat(logger.entries.get(1).message()).contains("OVERLOADED").contains("lowered to 25");
    }

    @Test
    void theDefaultConstructorWorksWithoutALogger() {
        new LoggingAlertSink().raise(new Alert(AlertType.SETTINGS_REJECTED, "responseTimeout must be <= leaseDuration / 2"));
    }
}
