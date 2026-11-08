package com.habitrain.core.scene.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ScenePrefetchWaitersTest {
    private record Reply(long session, String hash, boolean success) {}
    private final List<Reply> replies = new ArrayList<>();
    private final ScenePrefetchWaiters waiters = new ScenePrefetchWaiters(
            (session, hash, success) -> replies.add(new Reply(session, hash, success)));

    @Test void reloadCancellationIsSilentAndRebuildReportsSuccessToOriginalSession() {
        CompletableFuture<Boolean> old = new CompletableFuture<>();
        waiters.await(7, "asset", old);
        assertEquals(Set.of("asset"), waiters.suspend());
        old.complete(false);
        assertTrue(replies.isEmpty());
        CompletableFuture<Boolean> rebuilt = new CompletableFuture<>();
        waiters.resume("asset", rebuilt);
        rebuilt.complete(true);
        assertEquals(List.of(new Reply(7, "asset", true)), replies);
        assertTrue(waiters.assetHashes().isEmpty());
    }

    @Test void repeatedReloadsIgnoreEveryObsoleteCompletion() {
        CompletableFuture<Boolean> first = new CompletableFuture<>();
        CompletableFuture<Boolean> second = new CompletableFuture<>();
        CompletableFuture<Boolean> third = new CompletableFuture<>();
        waiters.await(7, "asset", first);
        waiters.suspend();
        waiters.resume("asset", second);
        waiters.suspend();
        waiters.resume("asset", third);
        first.complete(true);
        second.completeExceptionally(new IllegalStateException("old build"));
        assertTrue(replies.isEmpty());
        third.complete(true);
        assertEquals(List.of(new Reply(7, "asset", true)), replies);
    }

    @Test void sessionsSharingOneBuildEachReceiveOneTerminalReply() {
        CompletableFuture<Boolean> old = new CompletableFuture<>();
        waiters.await(7, "asset", old);
        waiters.await(8, "asset", old);
        waiters.await(7, "asset", old);
        waiters.suspend();
        old.cancel(false);
        waiters.resume("asset", CompletableFuture.completedFuture(true));
        assertEquals(List.of(new Reply(7, "asset", true), new Reply(8, "asset", true)), replies);
    }

    @Test void actualRebuildFailureStillReportsFailure() {
        waiters.await(7, "asset", new CompletableFuture<>());
        waiters.suspend();
        waiters.resume("asset", CompletableFuture.failedFuture(new IllegalStateException("new build")));
        assertEquals(List.of(new Reply(7, "asset", false)), replies);
    }

    @Test void missingManifestFailsTheSessionInsteadOfLeavingItPending() {
        waiters.await(7, "asset", new CompletableFuture<>());
        waiters.suspend();
        waiters.fail("asset");
        assertEquals(List.of(new Reply(7, "asset", false)), replies);
        assertTrue(waiters.assetHashes().isEmpty());
    }

    @Test void worldResetDropsWaitersAndLateCallbacksCannotReviveThem() {
        CompletableFuture<Boolean> old = new CompletableFuture<>();
        waiters.await(7, "asset", old);
        waiters.suspend();
        waiters.clear();
        waiters.resume("asset", CompletableFuture.completedFuture(true));
        old.complete(true);
        assertEquals(List.of(new Reply(7, "asset", false)), replies);
        assertTrue(waiters.assetHashes().isEmpty());
    }

    @Test void ordinaryCancellationWithoutReloadRemainsATerminalFailure() {
        CompletableFuture<Boolean> build = new CompletableFuture<>();
        waiters.await(7, "asset", build);
        build.cancel(false);
        assertEquals(List.of(new Reply(7, "asset", false)), replies);
    }
}
