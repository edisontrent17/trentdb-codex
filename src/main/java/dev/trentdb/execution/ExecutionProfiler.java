package dev.trentdb.execution;

import java.util.Locale;

public final class ExecutionProfiler {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("trentdb.profile", "false"));

    private ExecutionProfiler() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void log(String component, String event, long startNanos, String details) {
        if (!ENABLED) {
            return;
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        double elapsedMillis = elapsedNanos / 1_000_000.0d;
        if (details == null || details.isBlank()) {
            System.err.printf(Locale.ROOT, "PROFILE component=%s event=%s ms=%.3f%n", component, event, elapsedMillis);
            return;
        }
        System.err.printf(
                Locale.ROOT,
                "PROFILE component=%s event=%s ms=%.3f %s%n",
                component,
                event,
                elapsedMillis,
                details
        );
    }
}
