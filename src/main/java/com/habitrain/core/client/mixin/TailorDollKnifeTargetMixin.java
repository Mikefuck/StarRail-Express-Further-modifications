package com.habitrain.core.client.mixin;

import com.habitrain.core.game.sre.role.tailor.TailorDollEntity;
import io.wifi.starrailexpress.content.item.KnifeItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

/** Extend the upstream result without replacing Puppeteer and other existing knife targets. */
@Mixin(value = KnifeItem.class, remap = false, priority = 900)
public abstract class TailorDollKnifeTargetMixin {
    @WrapMethod(method = "getKnifeTarget")
    private static HitResult habitrain$includeDoll(Player user, Operation<HitResult> call) {
        HitResult original = call.call(user);
        HitResult doll = ProjectileUtil.getHitResultOnViewVector(user,
                e -> e instanceof TailorDollEntity prop && prop.complete(), 4F);
        if (doll instanceof EntityHitResult && (original == null || original.getType() == HitResult.Type.MISS
                || user.getEyePosition().distanceToSqr(doll.getLocation()) < user.getEyePosition().distanceToSqr(original.getLocation())))
            return doll;
        return original;
    }
}
