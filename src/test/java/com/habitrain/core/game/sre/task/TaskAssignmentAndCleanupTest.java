package com.habitrain.core.game.sre.task;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskAssignmentAndCleanupTest {
    @Test
    void rejectedInstancePredicateHasNoAssignmentSideEffects() {
        AtomicInteger assignments = new AtomicInteger();
        TaskDefinition rejected = new TaskDefinition.Builder("assign_test", "reject")
                .canAssign((p, t) -> {
                    assertEquals(0, t.getProgress());
                    assertEquals(1, t.getMaxProgress());
                    assertEquals(TaskInstance.State.ACTIVE, t.getState());
                    return false;
                }).onAssign((p, t) -> assignments.incrementAndGet()).build();
        assertNull(DlcTaskTracker.prepareAssignment(rejected, null, null));
        assertEquals(0, assignments.get());
        TaskDefinition accepted = new TaskDefinition.Builder("assign_test", "accept")
                .canAssign((p, t) -> true).build();
        assertNotNull(DlcTaskTracker.prepareAssignment(accepted, null, null));
        TaskDefinition prefilterRejected = new TaskDefinition.Builder("assign_test", "prefilter")
                .canAssignWithoutInstance(p -> false).canAssign((p, t) -> true).build();
        assertNull(DlcTaskTracker.prepareAssignment(prefilterRejected, null, null));
    }

    @Test
    void weightedSelectionRetriesRejectedCandidatesAndTerminates() {
        var pool = List.<Map.Entry<Object, Float>>of(
                new AbstractMap.SimpleEntry<>("bad", 2f),
                new AbstractMap.SimpleEntry<>("good", 1f));
        AtomicInteger attempts = new AtomicInteger();
        assertEquals("good", TaskSelector.selectAccepted(pool, () -> 0,
                key -> { attempts.incrementAndGet(); return "good".equals(key) ? key : null; }));
        assertEquals(2, attempts.get());
        attempts.set(0);
        assertNull(TaskSelector.selectAccepted(pool, () -> 0,
                key -> { attempts.incrementAndGet(); return null; }));
        assertEquals(2, attempts.get());
        assertEquals(2, pool.size());
    }

    @Test
    void parallelCleanupPreservesReplacementUnderSameTypeKey() {
        Object old = new Object();
        Object next = new Object();
        Map<String, Object> tasks = new HashMap<>();
        tasks.put("CUSTOM", next);
        assertFalse(DlcTaskTracker.removeSameTask(tasks, "CUSTOM", old));
        assertSame(next, tasks.get("CUSTOM"));
        tasks.put("SLEEP", old);
        assertTrue(DlcTaskTracker.removeSameTask(tasks, "SLEEP", old));
        assertFalse(tasks.containsKey("SLEEP"));
    }
}
