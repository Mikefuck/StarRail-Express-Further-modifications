package com.habitrain.core.client.mixin;

import com.habitrain.core.client.tailor.DollTailorClient;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerModel.class)
public abstract class DollTailorPlayerModelMixin<T extends LivingEntity> extends HumanoidModel<T> {
    protected DollTailorPlayerModelMixin(ModelPart root) { super(root); }
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void habitrain$threadHands(T entity, float walk, float speed, float age, float yaw, float pitch, CallbackInfo ci) {
        if (!(entity instanceof Player player) || !DollTailorClient.sewing(player)) return;
        head.xRot = .78F;
        rightArm.xRot = -1.35F + (float) Math.sin(age * 2.5) * .18F;
        leftArm.xRot = -1.35F - (float) Math.sin(age * 2.5) * .18F;
        rightArm.yRot = -.35F; leftArm.yRot = .35F;
        rightArm.zRot = .12F; leftArm.zRot = -.12F;
        PlayerModel<?> model = (PlayerModel<?>) (Object) this;
        model.rightSleeve.copyFrom(rightArm); model.leftSleeve.copyFrom(leftArm); model.hat.copyFrom(head);
    }
}
