package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.rest.EliminatedRestAreaService;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.agmas.harpymodloader.component.WorldModifierComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 休息区玩家的 SRE 存活/淘汰/击杀/复活语义守卫。
 *
 * <p>Mike 决策（MIXIN-SRV-02 / MIXIN-SRV-03 / MIXIN-SRV-04）：
 * <ul>
 *   <li>不再在 {@code RestAreaServerPlayerMixin} 中全局覆写原版 {@code ServerPlayer#isSpectator()}
 *       （避免服务端 {@code noPhysics=true; onGround=false} 与客户端 {@code ADVENTURE} 物理碰撞失步）；</li>
 *   <li>改为在 {@link GameUtils} 的 SRE 存活/淘汰/旁观判定点精确将休息区玩家视为已淘汰旁观者；</li>
 *   <li>无条件拦截涉及休息区玩家的任何 {@code GameUtils.killPlayer} 调用（含 {@code forceDeath} 及存活杀手攻击），
 *       彻底杜绝在休息区玩家身上刷取击杀数、金币、德林加子弹、角色击杀回调与尸体；</li>
 *   <li>在 {@code revivePlayerToItsRoom} / {@code revivePlayer} 执行传送前先行将休息区玩家迁回对局维度并清理淘汰表。</li>
 * </ul>
 */
@Mixin(value = GameUtils.class, remap = false)
public class RestAreaKillMixin {

    @Inject(
            method = "killPlayer(Lnet/minecraft/world/entity/player/Player;ZLnet/minecraft/world/entity/player/Player;Lnet/minecraft/resources/ResourceLocation;Z)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void habitrain$restAreaKillIsNotMatchJudgment(Player victim, boolean spawnBody, Player killer,
                                                                 ResourceLocation deathReason, boolean forceDeath,
                                                                 CallbackInfo ci) {
        if (victim == null) {
            return;
        }

        boolean victimResting = isResting(victim);
        boolean killerResting = isResting(killer);

        if (victimResting || killerResting) {
            ci.cancel();
            if (victimResting && victim.getHealth() < victim.getMaxHealth()) {
                victim.setHealth(victim.getMaxHealth());
            }
        }
    }

    @Inject(method = "isPlayerEliminatedIgnoreShitSplit", at = @At("HEAD"), cancellable = true, remap = false)
    private static void habitrain$restingIsEliminatedIgnoreSplit(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (isResting(player)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isPlayerEliminated", at = @At("HEAD"), cancellable = true, remap = false)
    private static void habitrain$restingIsEliminated(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (isResting(player)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isPlayerSpectator", at = @At("HEAD"), cancellable = true, remap = false)
    private static void habitrain$restingIsSpectator(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (isResting(player)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isPlayerSpectatingOrCreativeIgnoreShitSplit", at = @At("HEAD"), cancellable = true, remap = false)
    private static void habitrain$restingIsSpectatingOrCreative(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (isResting(player)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isPlayerAliveAndSurvivalIgnoreShitSplit", at = @At("HEAD"), cancellable = true, remap = false)
    private static void habitrain$restingIsNotAliveAndSurvivalIgnoreSplit(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (isResting(player)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = "isPlayerAliveAndSurvival(Lnet/minecraft/world/entity/player/Player;Lorg/agmas/harpymodloader/component/WorldModifierComponent;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void habitrain$restingIsNotAliveAndSurvival(Player player, WorldModifierComponent worldModifierComponent,
                                                               CallbackInfoReturnable<Boolean> cir) {
        if (isResting(player)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "revivePlayerToItsRoom", at = @At("HEAD"), remap = false)
    private static void habitrain$prepareReviveToRoom(ServerPlayer player, CallbackInfo ci) {
        if (player != null) {
            EliminatedRestAreaService.prepareUpstreamRevival(player);
            EliminatedRestAreaService.onExplicitUpstreamRevival(player);
        }
    }

    @Inject(method = "revivePlayer", at = @At("HEAD"), remap = false)
    private static void habitrain$prepareReviveAtPos(ServerPlayer player, double x, double y, double z, CallbackInfo ci) {
        if (player != null) {
            EliminatedRestAreaService.prepareUpstreamRevival(player);
            EliminatedRestAreaService.onExplicitUpstreamRevival(player);
        }
    }

    private static boolean isResting(Player player) {
        return player instanceof ServerPlayer serverPlayer && EliminatedRestAreaService.isResting(serverPlayer);
    }
}
