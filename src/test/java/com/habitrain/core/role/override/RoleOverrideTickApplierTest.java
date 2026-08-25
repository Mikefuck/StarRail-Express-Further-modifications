package com.habitrain.core.role.override;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleOverrideTickApplierTest {

    private static final ResourceLocation TARGET = ResourceLocation.parse("sre:killer");

    private Object previousSnapshot;

    @AfterEach
    void resetFreeze() throws Exception {
        RoleOverrideTickApplier.discardRoundFreeze();
        restoreSnapshot();
    }

    @Test
    void emptyModifiesReturnsWithoutServerOrTmmRoles() {
        assertDoesNotThrow(() -> RoleOverrideTickApplier.tick(null));
    }

    @Test
    void captureRoundQueuesReconcileUntilRelease() {
        RoleOverrideTickApplier.captureRound();
        assertTrue(RoleOverrideTickApplier.isRoundFrozen());
        assertEquals(0, RoleOverrideTickApplier.pendingSize());

        ModifyRoleDefinition next = modify("mid_round");
        RoleOverrideTickApplier.reconcile(Map.of(TARGET, next));

        assertTrue(RoleOverrideTickApplier.isRoundFrozen());
        assertEquals(1, RoleOverrideTickApplier.pendingSize());
        assertFalse(RoleOverrideTickApplier.gameplayModifies().containsKey(TARGET));

        // No flags/spawn patch, so releaseRound() does not touch TMMRoles.
        RoleOverrideTickApplier.releaseRound();
        assertFalse(RoleOverrideTickApplier.isRoundFrozen());
        assertEquals(0, RoleOverrideTickApplier.pendingSize());
    }

    @Test
    void gameplayModifiesStayFrozenWhileLiveSnapshotUpdates() throws Exception {
        ModifyRoleDefinition captured = modify("captured");
        ModifyRoleDefinition live = modify("live");
        setSnapshot(new EffectiveSnapshot(Map.of(), Map.of(TARGET, captured), List.of()));
        RoleOverrideTickApplier.captureRound();
        setSnapshot(new EffectiveSnapshot(Map.of(), Map.of(TARGET, live), List.of()));

        RoleOverrideEngine engine = RoleOverrideEngine.getInstance();
        assertEquals(live, engine.getActiveModify(TARGET));
        assertEquals(captured, engine.getGameplayModify(TARGET));
        assertNotSame(engine.getActiveModify(TARGET), engine.getGameplayModify(TARGET));

        RoleOverrideTickApplier.reconcile(Map.of(TARGET, live));
        assertEquals(1, RoleOverrideTickApplier.pendingSize());
        assertEquals(captured, engine.getGameplayModify(TARGET));
        assertEquals(live, engine.getActiveModify(TARGET));
    }

    private static ModifyRoleDefinition modify(String entryKey) {
        return ModifyRoleDefinition.builder()
                .sourceModId("example_mod")
                .entryKey(entryKey)
                .displayName(Component.literal(entryKey))
                .targetRoleId(TARGET)
                .build();
    }

    private void setSnapshot(EffectiveSnapshot snap) throws Exception {
        Field field = RoleOverrideEngine.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        RoleOverrideEngine engine = RoleOverrideEngine.getInstance();
        if (previousSnapshot == null) {
            previousSnapshot = field.get(engine);
        }
        field.set(engine, snap);
    }

    private void restoreSnapshot() throws Exception {
        if (previousSnapshot == null) {
            return;
        }
        Field field = RoleOverrideEngine.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        field.set(RoleOverrideEngine.getInstance(), previousSnapshot);
        previousSnapshot = null;
    }
}
