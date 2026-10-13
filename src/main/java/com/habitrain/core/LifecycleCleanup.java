package com.habitrain.core;

import org.slf4j.Logger;

/** Runs all mandatory cleanup steps and keeps every original failure for a final diagnostic. */
final class LifecycleCleanup {
    private final String context;
    private RuntimeException failures;

    LifecycleCleanup(String context) { this.context = context; }

    void run(String service, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable failure) {
            if (failures == null) failures = new RuntimeException(context + " cleanup failed");
            failures.addSuppressed(new IllegalStateException(service, failure));
        }
    }

    void report(Logger logger) {
        if (failures != null) logger.error("{}: {} cleanup services failed", context,
                failures.getSuppressed().length, failures);
    }

    RuntimeException failures() { return failures; }
}
