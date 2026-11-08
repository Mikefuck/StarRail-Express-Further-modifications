package com.habitrain.core.api.spi;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Modifier;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** A production bridge must not silently become a NOOP in unit-test bootstrap. */
class CoreSpiWiringTest {
    @Test void everyCommonBridgeUsesTheProductionRegistrar() throws Exception {
        Set<String> clientOnly = Set.of("sceneClient", "menuGateClient");
        int checked = 0;
        for (var field : CoreSpi.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getName().startsWith("NOOP_")
                    || !field.getType().isInterface() || !field.getType().getSimpleName().endsWith("Bridge")
                    || clientOnly.contains(field.getName())) continue;
            field.setAccessible(true);
            Object bridge = field.get(null);
            assertNotNull(bridge, field.getName());
            assertFalse(bridge.getClass().getName().startsWith(CoreSpi.class.getName() + "$"), field.getName());
            checked++;
        }
        assertTrue(checked >= 12, "Production bridge coverage: " + checked);
        assertTrue(CoreSpi.isExtraSlotReclaimInstalled());
        assertTrue(CoreSpi.isSceneFullyInstalled());
        assertTrue(CoreSpi.isRestAreaInstalled());
    }
}
