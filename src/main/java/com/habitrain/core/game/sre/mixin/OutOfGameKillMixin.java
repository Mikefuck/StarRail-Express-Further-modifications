package com.habitrain.core.game.sre.mixin;

import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 对局外禁止道具击杀（对齐上游：局外不可把人切旁观）。
 *
 * <p>根因说明：
 * <ul>
 *   <li>{@code GameUtils.killPlayer} → {@code getGameMode()}：gameMode 字段为 null 时
 *       会回落到 MURDER，且局末通常仍保留上一局的 GameMode 实例，不会因「无模式」短路。</li>
 *   <li>{@code GameMode.killPlayer} 仅在 {@code role==null && requiresAssignedRole()} 时 return；
 *       停电模式曾覆写为 false，clearRoleMap 后仍可击杀。</li>
 *   <li>上游也没有在 kill 入口硬查 {@code isRunning()}；本 mixin 用
 *       {@link SREGameWorldComponent#isRunning()}（ACTIVE|STOPPING）作为局外硬门禁，
 *       与「结束对局后道具无法使人切旁观」一致。forceDeath（指令/巫毒等）不拦。</li>
 * </ul>
 */
@Mixin(value = GameUtils.class, remap = false)
public class OutOfGameKillMixin {

    @Inject(
            method = "killPlayer(Lnet/minecraft/world/entity/player/Player;ZLnet/minecraft/world/entity/player/Player;Lnet/minecraft/resources/ResourceLocation;Z)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private static void habitrain$blockKillOutsideMatch(Player victim, boolean spawnBody, Player killer,
                                                         ResourceLocation deathReason, boolean forceDeath,
                                                         CallbackInfo ci) {
        if (forceDeath || victim == null) return;
        Level level = victim.level();
        if (level == null || level.isClientSide()) return;
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game != null && !game.isRunning()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 组件不可用时不拦，避免误伤正常对局路径
        }
    }
}
