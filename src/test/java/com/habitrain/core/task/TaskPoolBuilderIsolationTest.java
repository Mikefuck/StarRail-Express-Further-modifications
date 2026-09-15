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
    void emptyMurderPoolDoesNotFallBackToOtherModeTasks() {
        TaskCategory other_modeGood = new TaskCategory(
                "habitrain:other_mode_good", "其他模式", "habitrain:other_mode");
        TaskDefinition other_modeEat = new TaskDefinition.Builder("habitrain_core", "other_mode_eat")
                .category(other_modeGood)
                .build();

        List<TaskDefinition> selected = TaskPoolBuilder.selectCandidates(
                List.of(other_modeEat), id -> true, TaskCategory.MURDER,
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
