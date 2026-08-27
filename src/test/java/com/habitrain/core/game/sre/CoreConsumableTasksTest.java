package com.habitrain.core.game.sre;

import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreConsumableTasksTest {

    @Test
    void canonicalDefinitionsAreModeNeutralAndUseCoreOverlayTypes() {
        TaskDefinition eat = CoreConsumableTasks.createEatDefinition();
        TaskDefinition drink = CoreConsumableTasks.createDrinkDefinition();

        assertEquals("habitrain_core:eat", eat.getFullId());
        assertEquals("habitrain_core:drink", drink.getFullId());
        assertEquals(TaskCategory.ALL, eat.getCategory());
        assertEquals(TaskCategory.ALL, drink.getCategory());
        assertEquals(CoreConsumableTasks.EAT_BLOCK_TYPE_ID, eat.getBlockTypeId());
        assertEquals(CoreConsumableTasks.DRINK_BLOCK_TYPE_ID, drink.getBlockTypeId());
        assertTrue(eat.canRepeat());
        assertTrue(drink.canRepeat());
        assertNotNull(eat.getTimeImpact());
        assertNotNull(drink.getTimeImpact());
        assertEquals(10, eat.getTimeImpact().deltaSeconds());
        assertEquals(10, drink.getTimeImpact().deltaSeconds());
    }
}
