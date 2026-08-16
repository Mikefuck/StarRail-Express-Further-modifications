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

    /** SRE 背包中的任务点设置只要求普通管理员权限。 */
    public static boolean canAccessTaskSettings() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null && mc.player.hasPermissions(2);
    }

    public static void showDeniedMessage() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§c只有 OP 才能修改联机服务器配置"), true);
        }
    }
}
