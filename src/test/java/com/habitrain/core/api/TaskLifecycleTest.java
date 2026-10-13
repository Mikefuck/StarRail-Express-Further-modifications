package com.habitrain.core.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskLifecycleTest {
    private static TaskDefinition.Builder definition() {
        return new TaskDefinition.Builder("lifecycle_test", "task");
    }

    @Test
    void tickFailureBeatsTrueCheckerAndNotifiesOnceEvenWhenCallbackThrows() {
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        TaskInstance instance = new TaskInstance(definition()
                .onTick((p, t) -> { t.markFailed(); t.setFulfilled(true); })
                .completionChecker((p, t) -> true)
                .onFail((p, t) -> { failed.incrementAndGet(); t.tick(p); throw new IllegalStateException("injected"); })
                .onComplete((p, t) -> completed.incrementAndGet()).build());
        assertDoesNotThrow(() -> instance.tick(null));
        instance.tick(null);
        assertTrue(instance.isFailed());
        assertTrue(instance.isFulfilled());
        assertEquals(1, failed.get());
        assertEquals(0, completed.get());
        assertFalse(instance.claimCompletion());
    }

    @Test
    void checkerFailureCannotBeOverriddenToSuccess() {
        AtomicInteger complete = new AtomicInteger();
        TaskInstance instance = new TaskInstance(definition()
                .completionChecker((p, t) -> { t.markFailed(); return true; })
                .onComplete((p, t) -> complete.incrementAndGet()).build());
        instance.tick(null, () -> mode(false, true));
        assertTrue(instance.isFailed());
        assertEquals(0, complete.get());
    }

    @Test
    void modeTickFailureSkipsCheckerAndOverride() {
        AtomicInteger checker = new AtomicInteger();
        TaskInstance instance = new TaskInstance(definition().completionChecker((p, t) -> {
            checker.incrementAndGet(); return true;
        }).build());
        instance.tick(null, () -> mode(true, true));
        assertTrue(instance.isFailed());
        assertEquals(0, checker.get());
        assertFalse(instance.claimCompletion());
    }

    @Test
    void modeCanVetoLegacyCompletionRequestWithoutEndingTask() {
        TaskInstance instance = new TaskInstance(definition().onTick((p, t) -> t.setFulfilled(true)).build());
        instance.tick(null, () -> mode(false, false));
        assertEquals(TaskInstance.State.ACTIVE, instance.getState());
        assertFalse(instance.isFulfilled());
    }

    @Test
    void progressCallbackFailureStopsLaterCallbacksAndCompletion() {
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger modeProgress = new AtomicInteger();
        GameMode active = new GameMode() {
            public String getId() { return "test:progress"; }
            public String getDisplayName() { return "test"; }
            public List<TaskCategory> getTaskCategories() { return List.of(); }
            public boolean isActive(ServerLevel level) { return true; }
            public void onTaskProgressChange(ServerPlayer player, TaskInstance task, int old) { modeProgress.incrementAndGet(); }
        };
        TaskInstance instance = new TaskInstance(definition()
                .onTick((p, t) -> t.notifyProgress(p, 0, () -> active))
                .onProgressUpdate((p, t, old) -> t.markFailed())
                .completionChecker((p, t) -> true)
                .onFail((p, t) -> failures.incrementAndGet()).build());
        instance.tick(null, () -> active);
        assertEquals(1, failures.get());
        assertEquals(0, modeProgress.get());
        assertFalse(instance.claimCompletion());
    }

    @Test
    void timeoutSharesFailureNotificationAndSuccessClaimsAreExclusive() {
        AtomicInteger failures = new AtomicInteger();
        TaskInstance timeout = new TaskInstance(definition().timeLimit(1)
                .onFail((p, t) -> failures.incrementAndGet()).build());
        for (int i = 0; i < 25; i++) timeout.tick(null);
        assertEquals(1, failures.get());
        assertFalse(timeout.claimCompletion());
        AtomicInteger completions = new AtomicInteger();
        TaskInstance success = new TaskInstance(definition().completionChecker((p, t) -> true)
                .onComplete((p, t) -> { completions.incrementAndGet(); t.markFailed(); t.tick(p); }).build());
        success.tick(null);
        success.tick(null);
        assertTrue(success.isSucceeded());
        assertEquals(1, completions.get());
        assertTrue(success.claimCompletion());
        assertFalse(success.claimCompletion());
    }

    private static GameMode mode(boolean failTick, boolean override) {
        return new GameMode() {
            public String getId() { return "test:mode"; }
            public String getDisplayName() { return "test"; }
            public List<TaskCategory> getTaskCategories() { return List.of(); }
            public boolean isActive(ServerLevel level) { return true; }
            public void onTaskTick(ServerPlayer player, TaskInstance task) { if (failTick) task.markFailed(); }
            public Optional<Boolean> overrideCompletionCheck(ServerPlayer player, TaskInstance task) { return Optional.of(override); }
        };
    }
}
