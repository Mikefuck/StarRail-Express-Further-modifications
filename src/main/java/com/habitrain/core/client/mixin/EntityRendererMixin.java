package com.habitrain.core.client.mixin;

import com.habitrain.core.api.role.v2.client.RoleNameRenderRule;
import com.habitrain.core.client.role.RoleNameRenderHelper;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client nameplate adapter for v2 {@link RoleNameRenderRule}.
 *
 * <p>Applies NAMEPLATE rules before the vanilla name tag is drawn: hidden
 * rules cancel the render; color rules restyle the nameplate component.
 */
@Environment(EnvType.CLIENT)
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {

    @Inject(method = "renderNameTag", at = @At("HEAD"), cancellable = true)
    private void habitrain$maybeHideNameTag(T entity, Component component, PoseStack poseStack,
                                            MultiBufferSource buffer, int packedLight,
                                            float partialTick, CallbackInfo ci) {
        if (entity instanceof Player player) {
            RoleNameRenderRule rule = RoleNameRenderHelper.findNameplateRule(player);
            if (RoleNameRenderHelper.shouldHide(rule)) {
                ci.cancel();
            }
        }
    }

    @ModifyVariable(method = "renderNameTag", at = @At("HEAD"), argsOnly = true, index = 2)
    private Component habitrain$recolorNameTag(T entity, Component component) {
        if (entity instanceof Player player) {
            RoleNameRenderRule rule = RoleNameRenderHelper.findNameplateRule(player);
            return RoleNameRenderHelper.applyColor(rule, component);
        }
        return component;
    }
}
