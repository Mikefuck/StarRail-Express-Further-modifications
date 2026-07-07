package com.habitrain.core.client.mixin;

import io.wifi.starrailexpress.api.SRERole;
import org.agmas.noellesroles.client.InstinctRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin - 移除关灯模式警长的杀手透视（穿墙看人）。
 *
 * 警长（isVigilanteTeam()=true）因 canUseKiller=true 被判定为杀手队，
 * 从而获得了本应只属于杀手的透视。此处对警察阵营角色直接返回 false，
 * 外科式地移除透视，不改动 canUseKiller（保留警长的商店/左轮/手铐权限）。
 */
@Mixin(InstinctRenderer.class)
public class InstinctKillerTeamMixin {

    @Inject(
            method = "isKillerTeam",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void habitrain$noSheriffXray(SRERole role, CallbackInfoReturnable<Boolean> cir) {
        if (role != null && role.isVigilanteTeam()) {
            cir.setReturnValue(false);
        }
    }
}