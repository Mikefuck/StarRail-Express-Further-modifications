package com.habitrain.core.task;

import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskPoolBuilderIsolationTest {

    @Test
    void emptyMurderPoolDoesNotFallBackToBlackoutTasks() {
        TaskCategory blackoutGood = new TaskCategory(
                "habitrain:blackout_good", "停电好人", "habitrain:blackout");
        TaskDefinition blackoutEat = new TaskDefinition.Builder("habitrain_core", "blackout_eat")
                .category(blackoutGood)
                .build();

        List<TaskDefinition> selected = TaskPoolBuilder.selectCandidates(
                List.of(blackoutEat), id -> true, TaskCategory.MURDER,
                null, null, Set.of(), null);

        assertTrue(selected.isEmpty());
    }

    @Test
    void allCategoryCoreConsumablesRemainAvailableAcrossModes() {
        TaskDefinition eat = new TaskDefinition.Builder("habitrain_core", "eat")
                .category(TaskCategory.ALL)
                .build();

        List<TaskDefinition> selected = TaskPoolBuilder.selectCandidates(
                List.of(eat), id -> true, TaskCategory.REPAIR,
                null, null, Set.of(), null);

        assertEquals(List.of(eat), selected);
    }
}
