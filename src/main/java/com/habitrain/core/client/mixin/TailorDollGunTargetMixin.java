package com.habitrain.core.client.mixin;

import com.habitrain.core.game.sre.role.tailor.TailorDollEntity;
import io.wifi.starrailexpress.content.item.RevolverItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

@Mixin(value = {RevolverItem.class, org.agmas.noellesroles.content.item.BanditRevolverItem.class,
        org.agmas.noellesroles.content.item.ZeroOneFiveGunItem.class}, remap = false, priority = 900)
public abstract class TailorDollGunTargetMixin {
    @WrapMethod(method = "getGunTarget")
    private static HitResult habitrain$includeDoll(Player user, Operation<HitResult> call) {
        HitResult original = call.call(user);
        float range = user.getMainHandItem().is(org.agmas.noellesroles.init.ModItems.ZERO_ONE_FIVE_GUN) ? 30F : 20F;
        HitResult doll = ProjectileUtil.getHitResultOnViewVector(user, e -> e instanceof TailorDollEntity prop && prop.complete(), range);
        if (doll instanceof EntityHitResult && (original == null || original.getType() == HitResult.Type.MISS
                || user.getEyePosition().distanceToSqr(doll.getLocation()) < user.getEyePosition().distanceToSqr(original.getLocation())))
            return doll;
        return original;
    }
}
