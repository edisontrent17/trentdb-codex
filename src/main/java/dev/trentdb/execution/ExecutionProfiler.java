package dev.trentdb.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class ExecutionProfiler {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("trentdb.profile", "false"));
    private static final ThreadLocal<List<ProfileEvent>> CAPTURED_EVENTS = new ThreadLocal<>();

    private ExecutionProfiler() {
    }

    public static boolean enabled() {
        return ENABLED || CAPTURED_EVENTS.get() != null;
    }

    public static long start() {
        return enabled() ? System.nanoTime() : 0L;
    }

    public static void log(String component, String event, long startNanos, String details) {
        if (!enabled()) {
            return;
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        List<ProfileEvent> events = CAPTURED_EVENTS.get();
        if (events != null) {
            events.add(new ProfileEvent(component, event, elapsedNanos, details));
        }
        if (!ENABLED) {
            return;
        }
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

    public static <T> ProfileResult<T> capture(Supplier<T> supplier) {
        List<ProfileEvent> previousEvents = CAPTURED_EVENTS.get();
        ArrayList<ProfileEvent> events = new ArrayList<>();
        CAPTURED_EVENTS.set(events);
        try {
            return new ProfileResult<>(supplier.get(), events);
        } finally {
            if (previousEvents == null) {
                CAPTURED_EVENTS.remove();
            } else {
                CAPTURED_EVENTS.set(previousEvents);
            }
        }
    }

    public record ProfileEvent(String component, String event, long elapsedNanos, String details) {
        public double elapsedMillis() {
            return elapsedNanos / 1_000_000.0d;
        }
    }

    public record ProfileResult<T>(T value, List<ProfileEvent> events) {
        public ProfileResult {
            events = List.copyOf(events);
        }
    }
}
