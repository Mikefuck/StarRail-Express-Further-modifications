package com.habitrain.core.game.sre.mixin;

import io.wifi.starrailexpress.api.ExtraEffectRole;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * 修复风精灵（WIND_YAOSE）等 ExtraEffectRole 持有者的隐身效果"一闪即失"。
 *
 * 问题根因：
 * SRE 原版 ExtraEffectRole.serverTick（line 52-62）每 20 tick（1 秒）才重施效果，
 * 且仅在 getDuration() <= 21 时才补。角色重分配（如警长投票 setSheriff 触发
 * GameUtils.resetPlayer → RoleUtils.removeAllEffects）清掉所有效果后，
 * 最长需等 1 秒才会重施，造成"给一瞬间然后被清除"的视觉感。
 *
 * 本 mixin 在 serverTick HEAD 立即检测 INVISIBILITY 缺失并重施，
 * 不等 20 tick 边界，确保角色重分配后无缝衔接隐身。
 *
 * required:false 防 SRE 改名导致启动崩溃。
 */
@Mixin(value = ExtraEffectRole.class, remap = false)
public class ExtraEffectRoleMixin {

    @Shadow(remap = false)
    @Final
    public ArrayList<MobEffectInstance> playerEffects;

    @Shadow(remap = false)
    public MobEffectInstance getNewEffectInstance(MobEffectInstance instance) {
        throw new AssertionError("Shadowed");
    }

    @Inject(
            method = "serverTick",
            at = @At("HEAD"),
            remap = false
    )
    private void habitrain$immediateInvisibilityReapply(ServerPlayer player, CallbackInfo ci) {
        if (player == null || !player.isAlive()) return;
        // 仅检查持有 INVISIBILITY 效果的 ExtraEffectRole（如风精灵）
        for (MobEffectInstance eff : playerEffects) {
            if (eff.getEffect() == MobEffects.INVISIBILITY) {
                MobEffectInstance current = player.getEffect(MobEffects.INVISIBILITY);
                // 当前无隐身效果，或即将过期（duration < 21）→ 立即重施，不等 20 tick 边界
                if (current == null || current.getDuration() < 21) {
                    player.addEffect(getNewEffectInstance(eff));
                }
            }
        }
    }
}