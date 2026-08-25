package com.habitrain.core.internal;

/**
 * Marks the current thread as running habitrain_core lifecycle bootstrap.
 *
 * <p>{@link com.habitrain.core.api.GameModeRegistry#freeze()} and
 * {@link com.habitrain.core.api.TaskRegistry#freeze()} only take effect inside
 * {@link #run(Runnable)}. Public freeze calls from other mods are ignored.
 */
public final class CoreBootstrap {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private CoreBootstrap() {}

    public static void run(Runnable action) {
        if (action == null) {
            return;
        }
        DEPTH.set(DEPTH.get() + 1);
        try {
            action.run();
        } finally {
            int next = DEPTH.get() - 1;
            if (next <= 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(next);
            }
        }
    }

    public static boolean isInBootstrap() {
        return DEPTH.get() > 0;
    }
}
