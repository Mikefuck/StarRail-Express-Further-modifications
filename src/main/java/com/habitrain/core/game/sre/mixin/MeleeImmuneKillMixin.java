package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.component.FlowerGirlComponent;
import com.habitrain.core.game.sre.role.sins.SinDeathReasons;
import com.habitrain.core.game.sre.role.sins.component.PrideComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 防狼喷雾近战免疫 + 傲慢人群常规武器免疫（forceDeath=false 路径）。
 * 主路径仍是 AllowPlayerDeathWithKiller；此处兜底 cancel killPlayer HEAD。
 */
@Mixin(value = GameUtils.class, remap = false)
public class MeleeImmuneKillMixin {

    @Inject(
            method = "killPlayer(Lnet/minecraft/world/entity/player/Player;ZLnet/minecraft/world/entity/player/Player;Lnet/minecraft/resources/ResourceLocation;Z)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private static void habitrain$meleeImmune(Player victim, boolean spawnBody, Player killer,
                                              ResourceLocation deathReason, boolean forceDeath,
                                              CallbackInfo ci) {
        if (victim == null || forceDeath) return;
        if (deathReason == null) return;

        // Pride aura conventional-weapon immunity (non-force).
        if (PrideComponent.isPrideWeaponImmune(victim)
                && !SinDeathReasons.isForcePath(deathReason)
                && SinDeathReasons.isConventionalWeapon(deathReason)) {
            if (victim.getHealth() < victim.getMaxHealth()) {
                victim.setHealth(victim.getMaxHealth());
            }
            ci.cancel();
            return;
        }

        if (!FlowerGirlComponent.isMeleeImmune(victim)) return;
        String path = deathReason.getPath();
        if (path.contains("knife") || path.contains("bat") || path.contains("nunchuck")
                || path.contains("nunchaku") || path.contains("fist") || path.contains("melee")
                || "bat_hit".equals(path) || "knife_stab".equals(path)) {
            ci.cancel();
        }
    }
}
