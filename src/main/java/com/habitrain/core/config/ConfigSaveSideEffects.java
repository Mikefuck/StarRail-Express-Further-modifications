package com.habitrain.core.config;

/**
 * Pure decisions for what a successful local {@link ConfigManager#save()} must do next.
 * Kept out of the client handler so the singleplayer / dedicated split can be unit-tested.
 */
public final class ConfigSaveSideEffects {

    private ConfigSaveSideEffects() {}

    public record Decision(
            boolean markColorDirty,
            boolean sendC2S,
            boolean broadcastLan,
            boolean refreshIntegratedRoles) {}

    /**
     * @param hasConnection   client play connection exists
     * @param integratedHost  {@code Minecraft.getSingleplayerServer() != null}
     * @param canEditRemote   OP / menugate check for the current menu scope
     */
    public static Decision afterLocalSave(boolean hasConnection, boolean integratedHost, boolean canEditRemote) {
        if (!hasConnection) {
            return new Decision(true, false, false, false);
        }
        if (integratedHost) {
            return new Decision(true, false, true, true);
        }
        return new Decision(true, canEditRemote, false, false);
    }
}
