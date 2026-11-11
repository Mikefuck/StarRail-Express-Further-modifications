package com.habitrain.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
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
        assertDoesNotThrow(() -> cleanup.report(LoggerFactory.getLogger(LifecycleCleanupTest.class)));
    }

    @SuppressWarnings("removal")
    static Stream<Error> fatalFailures() {
        return Stream.of(new NoClassDefFoundError("synthetic linkage failure"),
                new OutOfMemoryError("synthetic; no allocation pressure"), new ThreadDeath());
    }

    @ParameterizedTest @MethodSource("fatalFailures")
    void fatalFailureEscapesAfterRemainingMandatoryCleanup(Error fatal) {
        LifecycleCleanup cleanup = new LifecycleCleanup("server stopping");
        List<String> calls = new ArrayList<>();
        cleanup.run("mode", () -> { calls.add("mode"); throw fatal; });
        cleanup.run("scene", () -> calls.add("scene"));
        cleanup.run("server reference", () -> calls.add("unbind"));
        assertEquals(List.of("mode", "scene", "unbind"), calls);
        assertSame(fatal, cleanup.failures().getSuppressed()[0].getCause());
        assertSame(fatal, assertThrows(fatal.getClass(),
                () -> cleanup.report(LoggerFactory.getLogger(LifecycleCleanupTest.class))));
    }

    @Test void nestedCleanupRetainsTheFirstFatalAndAllLaterFailures() {
        LifecycleCleanup cleanup = new LifecycleCleanup("nested world cleanup");
        Error first = new NoClassDefFoundError("first");
        Error second = new OutOfMemoryError("synthetic second");
        List<String> calls = new ArrayList<>();
        cleanup.run("world enumeration", () -> {
            cleanup.run("world mode", () -> { throw first; });
            cleanup.run("world vote", () -> calls.add("vote"));
        });
        cleanup.run("scene", () -> { throw second; });
        cleanup.run("server reference", () -> calls.add("unbind"));
        assertEquals(List.of("vote", "unbind"), calls);
        assertEquals(2, cleanup.failures().getSuppressed().length);
        assertSame(first, assertThrows(LinkageError.class,
                () -> cleanup.report(LoggerFactory.getLogger(LifecycleCleanupTest.class))));
    }
}
