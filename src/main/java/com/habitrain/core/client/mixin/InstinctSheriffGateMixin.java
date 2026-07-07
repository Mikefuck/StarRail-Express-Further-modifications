package com.habitrain.core.client.mixin;

import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.client.SREClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin - 移除关灯模式警长的杀手透视（全图夜视 + 伽马提亮 + 穿墙看人）。
 *
 * 警长（isVigilanteTeam()=true）因 canUseKiller=true → setCanUseInstinct(true)，
 * 使 isInstinctEnabled() 返回 true，从而获得了本应只属于杀手的透视。
 * 此处对警察阵营角色直接返回 false，一处关闭上述三项杀手透视。
 * 不改动 canUseKiller/canUseInstinct，因此警长的商店/左轮/手铐权限不受影响。
 */
@Mixin(SREClient.class)
public class InstinctSheriffGateMixin {

    @Inject(
            method = "isInstinctEnabled",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void habitrain$noSheriffInstinct(CallbackInfoReturnable<Boolean> cir) {
        SREGameWorldComponent gameComponent = SREClient.gameComponent;
        if (gameComponent == null) return;
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        SRERole role = gameComponent.getRole(player);
        if (role != null && role.isVigilanteTeam()) {
            cir.setReturnValue(false);
        }
    }
}