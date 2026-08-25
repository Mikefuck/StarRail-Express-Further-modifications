package com.habitrain.core.api;

import com.habitrain.core.internal.CoreBootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameModeRegistryTest {

    @AfterEach
    void tearDown() {
        GameModeRegistry.unfreezeForTests();
        TaskRegistry.unfreezeForTests();
        GameModeRegistry.resetTickGuards();
    }

    @Test
    void freezeOutsideBootstrapIsIgnored() {
        assertFalse(GameModeRegistry.isFrozen());
        assertFalse(TaskRegistry.isFrozen());
        GameModeRegistry.freeze();
        TaskRegistry.freeze();
        assertFalse(GameModeRegistry.isFrozen());
        assertFalse(TaskRegistry.isFrozen());
    }

    @Test
    void freezeInsideBootstrapIsIdempotent() {
        CoreBootstrap.run(() -> {
            GameModeRegistry.freeze();
            TaskRegistry.freeze();
            assertTrue(GameModeRegistry.isFrozen());
            assertTrue(TaskRegistry.isFrozen());
            GameModeRegistry.freeze();
            TaskRegistry.freeze();
            assertTrue(GameModeRegistry.isFrozen());
            assertTrue(TaskRegistry.isFrozen());
        });
        assertFalse(CoreBootstrap.isInBootstrap());
    }

    @Test
    void nestedBootstrapStillFreezes() {
        CoreBootstrap.run(() -> CoreBootstrap.run(GameModeRegistry::freeze));
        assertTrue(GameModeRegistry.isFrozen());
        assertFalse(CoreBootstrap.isInBootstrap());
    }

    @Test
    void tickGuardsSkipDuplicateServerTickAndGameTime() {
        assertTrue(GameModeRegistry.beginServerTick(7));
        assertFalse(GameModeRegistry.beginServerTick(7));
        assertTrue(GameModeRegistry.beginServerTick(8));

        assertTrue(GameModeRegistry.beginLevelTick(Level.OVERWORLD, 10L));
        assertFalse(GameModeRegistry.beginLevelTick(Level.OVERWORLD, 10L));
        assertTrue(GameModeRegistry.beginLevelTick(Level.OVERWORLD, 11L));
        assertFalse(GameModeRegistry.beginLevelTick(null, 11L));
    }

    @Test
    void isActiveInLevelNullIsFalse() {
        assertFalse(GameModeRegistry.isActiveInLevel(null));
    }
}
