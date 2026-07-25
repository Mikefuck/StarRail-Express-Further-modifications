package com.habitrain.core.client.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.agmas.noellesroles.client.NoellesrolesClient;
import org.agmas.noellesroles.client.hud.UnifiedSkillHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复 UnifiedSkillHud 就绪文案把 {@link KeyMapping} 直接塞进 %s，
 * 导致显示 {@code net.minecraft.class_4666@...}。
 * <p>
 * 上游文案 {@code hud.sre.skill.ready_shift} = 「就绪 (%s+%s)」，
 * 正确参数应为 {@link KeyMapping#getTranslatedKeyMessage()}。
 */
@Mixin(value = UnifiedSkillHud.class, remap = false)
public class UnifiedSkillHudReadyShiftMixin {

    @Redirect(
            method = "*",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;",
                    remap = true
            ),
            require = 0
    )
    private static MutableComponent habitrain$readyShiftKeyNames(String key, Object[] args) {
        if ("hud.sre.skill.ready_shift".equals(key)) {
            Object a0 = args != null && args.length > 0 ? args[0] : null;
            Object a1 = args != null && args.length > 1 ? args[1] : null;
            return Component.translatable(key, toKeyMessage(a0, true), toKeyMessage(a1, false));
        }
        return Component.translatable(key, args);
    }

    private static Component toKeyMessage(Object arg, boolean shift) {
        if (arg instanceof KeyMapping km) {
            return km.getTranslatedKeyMessage();
        }
        if (arg instanceof Component c) {
            return c;
        }
        try {
            if (shift) {
                Minecraft client = Minecraft.getInstance();
                if (client != null && client.options != null) {
                    return client.options.keyShift.getTranslatedKeyMessage();
                }
            } else if (NoellesrolesClient.abilityBind != null) {
                return NoellesrolesClient.abilityBind.getTranslatedKeyMessage();
            }
        } catch (Throwable ignored) {
        }
        return Component.literal(arg == null ? "?" : String.valueOf(arg));
    }
}
