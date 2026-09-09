package com.habitrain.core.client.mixin;

import com.habitrain.core.game.sre.role.sins.component.WrathComponent;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Adventure restrictions run BEFORE Fabric's AttackBlockCallback in Minecraft 1.21.1. */
@Mixin(MultiPlayerGameMode.class)
public abstract class WrathDoorAttackMixin {
    @WrapOperation(method = "startDestroyBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;blockActionRestricted(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/GameType;)Z"))
    private boolean habitrain$allowBerserkDoorAttack(LocalPlayer player, Level level, BlockPos pos,
                                                    GameType gameType, Operation<Boolean> original) {
        if (gameType == GameType.ADVENTURE && WrathComponent.canPryDoorWithBat(player, pos)) {
            return false;
        }
        return original.call(player, level, pos, gameType);
    }
}
