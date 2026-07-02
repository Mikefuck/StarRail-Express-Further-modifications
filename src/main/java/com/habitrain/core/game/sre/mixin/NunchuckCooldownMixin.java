package com.habitrain.core.game.sre.mixin;

import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.index.TMMItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "io.wifi.starrailexpress.network.original.NunchuckHitPayload")
public class NunchuckCooldownMixin {

    private static final int KILLER_ROLE_TYPE = 4;
    private static final int NUNCHUCK_COOLDOWN_TICKS = 1000;

    private static final Logger LOGGER = LoggerFactory.getLogger("NunchuckCooldownMixin");
    private static final ThreadLocal<Boolean> killHappened = ThreadLocal.withInitial(() -> false);

    @Inject(
            method = "onHurt",
            at = @At("HEAD"),
            remap = false
    )
    private static void habitrain$resetKillFlag(ServerPlayer attacker, Player target, int direction_,
                                                CallbackInfo ci) {
        killHappened.set(false);
    }

    @Inject(
            method = "onHurt",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/game/GameUtils;killPlayer(Lnet/minecraft/world/entity/player/Player;ZLnet/minecraft/world/entity/player/Player;Lnet/minecraft/resources/ResourceLocation;)V"
            ),
            remap = false
    )
    private static void habitrain$markKillHappened(ServerPlayer attacker, Player target, int direction_,
                                                   CallbackInfo ci) {
        killHappened.set(true);
    }

    @Inject(
            method = "onHurt",
            at = @At("TAIL"),
            remap = false
    )
    private static void habitrain$applyKillerCooldown(ServerPlayer attacker, Player target, int direction_,
                                                      CallbackInfo ci) {
        if (!Boolean.TRUE.equals(killHappened.get())) {
            return;
        }
        killHappened.set(false);

        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(attacker.level());
            var role = game.getRole(attacker);
            if (role == null || role.getRoleType() != KILLER_ROLE_TYPE) {
                return;
            }

            attacker.getCooldowns().addCooldown(TMMItems.NUNCHUCK, NUNCHUCK_COOLDOWN_TICKS);

            LOGGER.debug(
                    "[NunchuckCD] 杀手 {} 使用双节棍击杀，设置冷却=1000 ticks (50秒)",
                    attacker.getName().getString());

        } catch (Exception e) {
            LOGGER.warn("[NunchuckCD] 设置冷却时出错", e);
        }
    }
}
