package com.habitrain.core.internal;

/** Test-only bootstrap; excluded from the mod JAR and never weakens production origin checks. */
public final class CoreLifecycleTestSupport {
    private CoreLifecycleTestSupport() {}
    public static void run(Runnable action) {
        try {
            var enter = CoreLifecycleScope.class.getDeclaredMethod("runWithinScope", Runnable.class);
            enter.setAccessible(true);
            enter.invoke(null, action);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
