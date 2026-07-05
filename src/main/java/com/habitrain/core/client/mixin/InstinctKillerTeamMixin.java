package com.habitrain.core.client.mixin;

import com.habitrain.core.game.blackout.BlackoutRoles;
import io.wifi.starrailexpress.api.SRERole;
import org.agmas.noellesroles.client.InstinctRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin - 移除关灯模式警长的杀手透视（穿墙看人）。
 *
 * 【背景】
 * SRE/noellesroles 的 {@code InstinctRenderer.isKillerTeam(SRERole)} 判定本地玩家是否
 * 拥有杀手透视：返回 true 时，周围 20 格内的其它存活玩家会被以角色颜色穿墙高亮。
 * 关灯模式警长在 {@link BlackoutRoles#SHERIFF} 中因 {@code canUseKiller=true} 被判定为
 * 杀手队，从而获得了本应只属于杀手的透视。
 *
 * 【修复方式】
 * 在 {@code isKillerTeam} 的 HEAD 注入：当传入角色为关灯警长时直接返回 false，
 * 外科式地移除透视，**不**改动 {@code canUseKiller}（保留警长的商店/左轮/手铐权限与
 * 任务方块 instinct）。杀手自身不受影响。
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
        if (role != null && BlackoutRoles.SHERIFF_ID.equals(role.identifier())) {
            cir.setReturnValue(false);
        }
    }
}