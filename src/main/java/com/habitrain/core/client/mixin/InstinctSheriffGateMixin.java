package com.habitrain.core.client.mixin;

import com.habitrain.core.game.blackout.BlackoutRoles;
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
 * 【背景】
 * SRE 的"instinct"系统由 {@link SREClient#isInstinctEnabled()} 作为总闸门，统一控制三项
 * 客户端能力：夜视（IsNightVisionMixin 伪造 NIGHT_VISION）、伽马提亮
 * （TrueDarknessLightmapTextureManagerMixin 读取 instinctLightLevel）、穿墙玩家高亮
 * （MinecraftClientMixin + OnGetInstinctHighlight 事件）。
 * 关灯警长因 {@code canUseKiller=true} → {@code setCanUseInstinct(true)}，使
 * {@code isInstinctEnabled()} 返回 true，从而获得了本应只属于杀手的透视。
 *
 * 【修复方式】
 * 在 {@code isInstinctEnabled()} 的 HEAD 注入：本地玩家角色为关灯警长时直接返回 false，
 * 一处关闭上述三项杀手透视。**不**改动 {@code canUseKiller}/{@code canUseInstinct}，
 * 因此警长的商店/左轮/手铐权限不受影响；任务方块透视由 {@code TaskBlockOverlayRenderer}
 * 基于 {@code isKiller()} 单独判定，不会因本 mixin 而丢失。
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
        if (role != null && BlackoutRoles.SHERIFF_ID.equals(role.identifier())) {
            cir.setReturnValue(false);
        }
    }
}