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
        TaskCategory otherModeGood = new TaskCategory(
                "habitrain:other_mode_good", "other mode", "habitrain:other_mode");
        TaskDefinition otherModeTask = new TaskDefinition.Builder("habitrain_core", "other_mode_task")
                .category(otherModeGood)
                .build();

        List<TaskDefinition> selected = TaskPoolBuilder.selectCandidates(
                List.of(otherModeTask), id -> true, TaskCategory.MURDER,
                null, Set.of());

        assertTrue(selected.isEmpty());
    }

    @Test
    void allCategoryTasksRemainAvailableAcrossModes() {
        TaskDefinition allModes = new TaskDefinition.Builder("habitrain_core", "some_all_task")
                .category(TaskCategory.ALL)
                .build();

        List<TaskDefinition> selected = TaskPoolBuilder.selectCandidates(
                List.of(allModes), id -> true, TaskCategory.REPAIR,
                null, Set.of());

        assertEquals(List.of(allModes), selected);
    }

    @Test
    void poolEligibleDefaultsToTrue() {
        TaskDefinition def = new TaskDefinition.Builder("habitrain_core", "some_dlc_task").build();
        assertTrue(def.isPoolEligible());
    }

    /**
     * Registration-only definitions (poolEligible=false, i.e. upstream SRE task mirrors)
     * must stay out of the dispatch pool even when their ID is absent from the legacy
     * fallback list and the map/category allow them.
     */
    @Test
    void registeredMirrorDefinitionsAreExcludedFromPool() {
        TaskDefinition mirror = new TaskDefinition.Builder("habitrain_core", "sleep")
                .category(TaskCategory.MURDER)
                .poolEligible(false)
                .build();
        TaskDefinition real = new TaskDefinition.Builder("habitrain_core", "some_new_dlc_task")
                .category(TaskCategory.MURDER)
                .build();

        List<TaskDefinition> selected = TaskPoolBuilder.selectCandidates(
                List.of(mirror, real), id -> true, TaskCategory.MURDER,
                null, Set.of());

        assertEquals(List.of(real), selected);
    }

    /**
     * The legacy fallback ID list still guards the pool: a definition that forgot the
     * poolEligible(false) flag but whose ID hits the builtin list must be excluded.
     */
    @Test
    void legacyBuiltinIdListStillGuardsPool() {
        TaskDefinition unflaggedMirror = new TaskDefinition.Builder("habitrain_core", "meditate")
                .category(TaskCategory.MURDER)
                .build();

        List<TaskDefinition> selected = TaskPoolBuilder.selectCandidates(
                List.of(unflaggedMirror), id -> true, TaskCategory.MURDER,
                null, Set.of("meditate"));

        assertTrue(selected.isEmpty());
    }

    /**
     * Every upstream-task mirror from the single-source table
     * ({@code game.sre.SreTaskMirrors}) — and nothing else — must stay out of the DLC pool,
     * both by the poolEligible flag and by the derived legacy ID list.
     */
    @Test
    void everyUpstreamMirrorStaysOutOfTheDlcPool() {
        var mirrors = com.habitrain.core.game.sre.SreTaskMirrors.all();
        assertTrue(mirrors.size() >= 22, "mirror table shrank unexpectedly");

        List<TaskDefinition> flagged = mirrors.stream()
                .map(mirror -> new TaskDefinition.Builder("habitrain_core", mirror.id())
                        .category(TaskCategory.ALL)
                        .poolEligible(false)
                        .build())
                .toList();
        assertTrue(TaskPoolBuilder.selectCandidates(
                        flagged, id -> true, TaskCategory.ALL, null,
                        com.habitrain.core.game.sre.SreTaskMirrors.ids()).isEmpty(),
                "flagged mirrors must be excluded by poolEligible(false)");

        List<TaskDefinition> unflagged = mirrors.stream()
                .map(mirror -> new TaskDefinition.Builder("habitrain_core", mirror.id())
                        .category(TaskCategory.ALL)
                        .build())
                .toList();
        assertTrue(TaskPoolBuilder.selectCandidates(
                        unflagged, id -> true, TaskCategory.ALL, null,
                        com.habitrain.core.game.sre.SreTaskMirrors.ids()).isEmpty(),
                "the legacy fallback list must cover every mirror id");
    }
}