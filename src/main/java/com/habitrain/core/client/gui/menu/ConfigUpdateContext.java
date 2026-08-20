package com.habitrain.core.client.gui.menu;

import com.habitrain.core.network.ConfigUpdateScope;

/** Holds the access scope of the currently active configuration flow on the client. */
public final class ConfigUpdateContext {
    private static volatile ConfigUpdateScope currentScope = ConfigUpdateScope.FULL_MOD_MENU;

    private ConfigUpdateContext() {}

    public static ConfigUpdateScope currentScope() {
        return currentScope;
    }

    public static void setCurrentScope(ConfigUpdateScope scope) {
        currentScope = scope == null ? ConfigUpdateScope.FULL_MOD_MENU : scope;
    }

    public static void reset() {
        currentScope = ConfigUpdateScope.FULL_MOD_MENU;
    }
}
