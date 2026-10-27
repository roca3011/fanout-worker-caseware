package com.caseware.templateupdates.fixtures;

import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

/** Registered as the JVM's {@link System.LoggerFinder} for tests so log output can be asserted. */
public final class CapturingLoggerFinder extends System.LoggerFinder {

    public record Entry(System.Logger.Level level, String message, Throwable thrown) {}

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    public static void clear() {
        ENTRIES.clear();
    }

    public static boolean has(System.Logger.Level level, String fragment) {
        return ENTRIES.stream().anyMatch(e -> e.level() == level && e.message().contains(fragment));
    }

    public static boolean hasAny(String fragment) {
        return ENTRIES.stream().anyMatch(e -> e.message().contains(fragment));
    }

    public static Entry find(System.Logger.Level level, String fragment) {
        return ENTRIES.stream()
                .filter(e -> e.level() == level && e.message().contains(fragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + level + " log containing '" + fragment + "'; got " + ENTRIES));
    }

    @Override
    public System.Logger getLogger(String name, Module module) {
        return new System.Logger() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public boolean isLoggable(Level level) {
                return true;
            }

            @Override
            public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
                ENTRIES.add(new Entry(level, msg, thrown));
            }

            @Override
            public void log(Level level, ResourceBundle bundle, String format, Object... params) {
                String text = params == null || params.length == 0 ? format : MessageFormat.format(format, params);
                ENTRIES.add(new Entry(level, text, null));
            }
        };
    }
}
