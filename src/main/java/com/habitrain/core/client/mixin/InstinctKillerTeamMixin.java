package com.habitrain.core.client.mixin;

import io.wifi.starrailexpress.api.SRERole;
import org.agmas.noellesroles.client.utils.InstinctManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin - 移除关灯模式警长的杀手透视（穿墙看人）。
 *
 * <p>新版 SRE 将本能渲染从 {@code InstinctRenderer} 重构为
 * {@link InstinctManager}；警长（isVigilanteTeam()=true）因
 * canUseKiller=true 被 {@link SRERole#isKillerTeam()} 判定为杀手队，
 * 从而获得了本应只属于杀手的透视。此处对警察阵营角色直接返回 false，
 * 外科式地移除透视，不改动 canUseKiller（保留警长的商店/左轮/手铐权限）。
 */
@Mixin(value = InstinctManager.class, remap = false)
public class InstinctKillerTeamMixin {

    @Redirect(
            method = "getCommonAliveInstinct",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/api/SRERole;isKillerTeam()Z"
            ),
            require = 0
    )
    private static boolean habitrain$noSheriffXray(SRERole role) {
        return role != null && !role.isVigilanteTeam() && role.isKillerTeam();
    }
}
