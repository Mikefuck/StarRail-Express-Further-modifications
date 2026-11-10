package com.habitrain.core.client.mixin;

import com.habitrain.core.game.sre.role.tailor.TailorDollEntity;
import io.wifi.starrailexpress.content.item.SniperRifleItem;
import io.wifi.starrailexpress.util.SniperProjectileUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

@Mixin(value = SniperRifleItem.class, remap = false, priority = 900)
public abstract class TailorDollSniperTargetMixin {
    @WrapMethod(method = "getGunTarget")
    private static HitResult habi$target(Player player, Operation<HitResult> call) {
        HitResult original = call.call(player);
        HitResult doll = SniperProjectileUtil.getSniperHitResult(player,
                entity -> entity instanceof TailorDollEntity d && d.complete(), 200F);
        if (doll instanceof EntityHitResult && (original == null || original.getType() == HitResult.Type.MISS
                || player.getEyePosition().distanceToSqr(doll.getLocation())
                < player.getEyePosition().distanceToSqr(original.getLocation()))) return doll;
        return original;
    }
}
