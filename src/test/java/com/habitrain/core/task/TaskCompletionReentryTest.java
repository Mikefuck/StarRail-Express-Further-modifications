package com.habitrain.core.task;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskCompletionReentryTest {
    @Test
    void definitionCompletionReplacementSurvivesSettlementAndModeReentry() {
        TaskManager manager = TaskManager.getInstance();
        UUID player = UUID.randomUUID();
        TaskInstance next = new TaskInstance(new TaskDefinition.Builder("reentry_test", "next").build());
        TaskInstance original = new TaskInstance(new TaskDefinition.Builder("reentry_test", "original")
                .completionChecker((p, t) -> true)
                .onComplete((p, t) -> manager.setActiveTask(player, next)).build());
        manager.setActiveTask(player, original);
        AtomicInteger rewards = new AtomicInteger();
        try {
            original.tick(null);
            manager.settleTask(player, original, () -> {
                rewards.incrementAndGet();
                manager.settleTask(player, original, () -> fail("duplicate settlement"));
            });
            assertSame(next, manager.getActiveTask(player));
            assertEquals(1, rewards.get());
        } finally { manager.removeActiveTask(player); }
    }

    @Test
    void modeCompletionReplacementSurvivesOldSlotRemoval() {
        TaskManager manager = TaskManager.getInstance();
        UUID player = UUID.randomUUID();
        TaskInstance next = new TaskInstance(new TaskDefinition.Builder("reentry_test", "next").build());
        TaskInstance original = new TaskInstance(new TaskDefinition.Builder("reentry_test", "original")
                .completionChecker((p, t) -> true).build());
        manager.setActiveTask(player, original);
        try {
            original.tick(null);
            manager.settleTask(player, original, () -> manager.setActiveTask(player, next));
            assertSame(next, manager.getActiveTask(player));
            assertFalse(manager.removeActiveTask(player, original));
            assertSame(next, manager.getActiveTask(player));
        } finally { manager.removeActiveTask(player); }
    }
}
