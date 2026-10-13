package com.habitrain.core;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LifecycleCleanupTest {
    @Test void callbackFailureCannotSkipMandatoryCleanupAndAllFailuresAreRetained() {
        LifecycleCleanup cleanup = new LifecycleCleanup("disconnect");
        List<String> calls = new ArrayList<>();
        RuntimeException first = new RuntimeException("mode.onPlayerLeave");
        cleanup.run("mode callback", () -> { calls.add("callback"); throw first; });
        cleanup.run("action", () -> calls.add("action"));
        cleanup.run("scene", () -> { calls.add("scene"); throw new IllegalStateException("scene"); });
        cleanup.run("server reference", () -> calls.add("unbind"));
        assertEquals(List.of("callback", "action", "scene", "unbind"), calls);
        assertEquals(2, cleanup.failures().getSuppressed().length);
        assertSame(first, cleanup.failures().getSuppressed()[0].getCause());
    }
}
