package com.habitrain.core.internal;

/** Fatal VM, thread and binary-linkage failures must escape addon isolation boundaries. */
public final class FailurePolicy {
    private FailurePolicy() {}

    public static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError;
    }

    public static void rethrowIfFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError error) throw error;
        if (failure instanceof ThreadDeath error) throw error;
        if (failure instanceof LinkageError error) throw error;
    }
}
