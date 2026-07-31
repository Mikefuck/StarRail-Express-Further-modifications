package com.habitrain.core.client.gui.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 客户端配置编辑权限助手（替代旧 LiveConfigAccess）。 */
public final class MenuPermissions {
    private MenuPermissions() {}

    public static boolean canEditRemoteConfigs() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return true;
        if (mc.getConnection() == null) return true;
        if (mc.getSingleplayerServer() != null) return true;
        return mc.player != null && mc.player.hasPermissions(4);
    }

    public static void showDeniedMessage() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§c只有 OP 才能修改联机服务器配置"), true);
        }
    }
}
