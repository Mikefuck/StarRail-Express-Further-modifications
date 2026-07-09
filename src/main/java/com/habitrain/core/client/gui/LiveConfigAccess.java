package com.habitrain.core.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client-side helper for live config editing permissions.
 *
 * Remote multiplayer configs are editable only by OP players. Singleplayer
 * and disconnected local editing are treated as editable.
 */
public final class LiveConfigAccess {
    private LiveConfigAccess() {
    }

    public static boolean canEditRemoteConfigs() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return true;
        }
        if (mc.getConnection() == null) {
            return true;
        }
        if (mc.getSingleplayerServer() != null) {
            return true;
        }
        return mc.player != null && mc.player.hasPermissions(4);
    }

    public static void showDeniedMessage() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§c只有 OP 才能修改联机服务器配置"), true);
        }
    }
}
