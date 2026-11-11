package com.habitrain.core.api.match;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MatchEventsIsolationTest {
    @SuppressWarnings("removal")
    static Stream<Error> fatalFailures() {
        return Stream.of(new NoClassDefFoundError("synthetic linkage failure"),
                new OutOfMemoryError("synthetic; no allocation pressure"), new ThreadDeath());
    }

    static Stream<Throwable> nonFatalFailures() {
        return Stream.of(new IllegalStateException("addon failure"), new AssertionError("addon assertion"));
    }

    @ParameterizedTest @MethodSource("fatalFailures")
    void startedEventPropagatesFatalFailure(Error fatal) {
        var event = MatchEvents.createStartedEvent();
        var next = new AtomicBoolean();
        event.register(level -> { throw fatal; });
        event.register(level -> next.set(true));
        assertSame(fatal, assertThrows(fatal.getClass(), () -> event.invoker().onMatchStarted(null)));
        assertFalse(next.get());
    }

    @ParameterizedTest @MethodSource("fatalFailures")
    void endedEventPropagatesFatalFailure(Error fatal) {
        var event = MatchEvents.createRoundEndedEvent();
        var next = new AtomicBoolean();
        event.register((level, settlement) -> { throw fatal; });
        event.register((level, settlement) -> next.set(true));
        assertSame(fatal, assertThrows(fatal.getClass(), () -> event.invoker().onRoundEnded(null, null)));
        assertFalse(next.get());
    }

    @ParameterizedTest @MethodSource("nonFatalFailures")
    void ordinaryStartedListenerFailureDoesNotSkipOtherAddons(Throwable failure) {
        var event = MatchEvents.createStartedEvent();
        var next = new AtomicBoolean();
        event.register(level -> throwUnchecked(failure));
        event.register(level -> next.set(true));
        assertDoesNotThrow(() -> event.invoker().onMatchStarted(null));
        assertTrue(next.get());
    }

    @ParameterizedTest @MethodSource("nonFatalFailures")
    void ordinaryEndedListenerFailureDoesNotSkipOtherAddons(Throwable failure) {
        var event = MatchEvents.createRoundEndedEvent();
        var next = new AtomicBoolean();
        event.register((level, settlement) -> throwUnchecked(failure));
        event.register((level, settlement) -> next.set(true));
        assertDoesNotThrow(() -> event.invoker().onRoundEnded(null, null));
        assertTrue(next.get());
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof Error error) throw error;
        throw (RuntimeException) failure;
    }
}
