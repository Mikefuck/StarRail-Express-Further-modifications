package com.habitrain.core;

import com.habitrain.core.internal.FailurePolicy;
import org.slf4j.Logger;

/** Best-effort cleanup; report preserves diagnostics and propagates the first fatal failure. */
final class LifecycleCleanup {
    private final String context;
    private RuntimeException failures;
    private Throwable fatalFailure;

    LifecycleCleanup(String context) { this.context = context; }

    void run(String service, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable failure) {
            if (fatalFailure == null && FailurePolicy.isFatal(failure)) fatalFailure = failure;
            if (failures == null) failures = new RuntimeException(context + " cleanup failed");
            failures.addSuppressed(new IllegalStateException(service, failure));
        }
    }

    void report(Logger logger) {
        try {
            if (failures != null) logger.error("{}: {} cleanup services failed", context,
                    failures.getSuppressed().length, failures);
        } finally {
            FailurePolicy.rethrowIfFatal(fatalFailure);
        }
    }

    RuntimeException failures() { return failures; }
}
