package com.habitrain.core.client.gui.menu;

import com.habitrain.core.network.ConfigUpdateScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigUpdateContextTest {

    @Test
    void resetPreventsBackpackScopeFromLeakingIntoLaterConfigurationSaves() {
        ConfigUpdateContext.setCurrentScope(ConfigUpdateScope.BACKPACK_TASKS);

        ConfigUpdateContext.reset();

        assertEquals(ConfigUpdateScope.FULL_MOD_MENU, ConfigUpdateContext.currentScope());
    }
}
