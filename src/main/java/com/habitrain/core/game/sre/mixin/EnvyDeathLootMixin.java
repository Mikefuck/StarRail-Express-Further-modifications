package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.EnvyDeathLoot;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.wifi.starrailexpress.api.GameMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GameMode.class)
public abstract class EnvyDeathLootMixin {
    @WrapMethod(method = "killPlayer")
    private void habitrain$preserveDeathLoot(Player victim, boolean spawnBody, Player killer,
                                            ResourceLocation reason, boolean forceDeath, Operation<Void> original) {
        EnvyDeathLoot.duringKill(victim,
                () -> original.call(victim, spawnBody, killer, reason, forceDeath));
    }
}
