package com.habitrain.core.client.gui.menu;

import com.habitrain.core.client.menu.MenuAccessGuard;
import com.habitrain.core.network.ConfigUpdateScope;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 客户端配置编辑权限助手（替代旧 LiveConfigAccess）。 */
public final class MenuPermissions {
    private MenuPermissions() {}

    public static boolean canEditRemoteConfigs() {
        return canEditRemoteConfigs(ConfigUpdateContext.currentScope());
    }

    public static boolean canEditRemoteConfigs(ConfigUpdateScope scope) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return true;
        if (mc.getConnection() == null) return true;
        if (mc.getSingleplayerServer() != null) return true;
        if (mc.player == null || !mc.player.hasPermissions(2)) return false;
        return scope != ConfigUpdateScope.FULL_MOD_MENU || MenuAccessGuard.isScreenAllowed();
    }

    /** SRE 背包中的受限设置只要求 OP2，不受 Mod Menu 授权名单影响。 */
    public static boolean canAccessTaskSettings() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null && mc.player.hasPermissions(2);
    }

    public static void showDeniedMessage() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§c需要 OP2；完整 Mod 菜单还需服务器后台单独授权"), true);
        }
    }
}
