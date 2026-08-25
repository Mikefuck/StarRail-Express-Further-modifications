package com.habitrain.core.client.mixin;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.BlackoutKeyHandler;
import com.habitrain.core.client.EliminatedRestPromptState;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.agmas.noellesroles.client.NoellesrolesClient;
import org.agmas.noellesroles.client.hud.UnifiedSkillHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * 修复 UnifiedSkillHud 就绪文案把 {@link KeyMapping} 直接塞进 %s，
 * 导致显示 {@code net.minecraft.class_4666@...}。
 * <p>
 * 上游文案 {@code hud.sre.skill.ready_shift} = 「就绪 (%s+%s)」，
 * 正确参数应为 {@link KeyMapping#getTranslatedKeyMessage()}。
 * <p>
 * SRE 4.3.0 javap（禁止改成 {@code $0}）：
 * <ul>
 *   <li>{@code lambda$register$0(Style)} — 内部 {@code withColor}，不是 HUD 回调</li>
 *   <li>{@code lambda$register$1(FakeGuiGraphics, DeltaTracker)} — 真正的 HUD 回调</li>
 * </ul>
 * 休息提示通过 Redirect {@code RoleSkill.getDefinitions(SRERole)} <b>追加</b>
 * 到技能列表（空列表也能插入，避免 early-return 把提示吞掉）。
 */
@Mixin(value = UnifiedSkillHud.class, remap = false)
public class UnifiedSkillHudReadyShiftMixin {
    private static final RoleSkill.Definition ELIMINATED_REST_PROMPT = RoleSkill.skill(
                    ResourceLocation.fromNamespaceAndPath(HabiTrainCore.MOD_ID, "enter_rest_area"),
                    "skill.habitrain_core.enter_rest_area",
                    context -> false)
            .noCastCCA(true)
            .noAnnouncement()
            .showOnHud(true)
            .build();

    /**
     * Append the rest-area prompt to the live skill list.
     * {@code showOnHud=true} so the rest row survives the HUD visible filter.
     * Empty original lists still receive the prompt so the HUD does not early-return.
     */
    @Redirect(
            method = "lambda$register$1",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/api/RoleSkill;getDefinitions(Lio/wifi/starrailexpress/api/SRERole;)Ljava/util/List;",
                    remap = false
            )
    )
    private static List<RoleSkill.Definition> habitrain$addRestAreaPrompt(SRERole role) {
        List<RoleSkill.Definition> definitions = RoleSkill.getDefinitions(role);
        if (!EliminatedRestPromptState.isVisible()) {
            return definitions;
        }
        List<RoleSkill.Definition> result = new ArrayList<>(definitions);
        result.add(ELIMINATED_REST_PROMPT);
        return result;
    }

    /**
     * The rest toggle deliberately reuses the registered vote binding. Make
     * that configured key explicit on this synthetic skill row so the player
     * does not press the upstream role-ability key and trigger the dead role's
     * selection UI.
     */
    @Redirect(
            method = "lambda$register$1",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;",
                    remap = true
            )
    )
    private static MutableComponent habitrain$restAreaKeyName(String key) {
        MutableComponent translated = Component.translatable(key);
        if (!"skill.habitrain_core.enter_rest_area".equals(key)) {
            return translated;
        }
        return translated.append(" (")
                .append(BlackoutKeyHandler.getBoundKeyDisplay())
                .append(")");
    }

    /**
     * The panel is anchored to the bottom-right corner; the synthetic rest
     * row is therefore the row nearest the screen bottom. Upstream role HUDs
     * that self-draw near the bottom-right corner (e.g. the athlete's
     * 「疾跑就绪-按G激活」 at {@code guiHeight-32}) overlap the lower half of
     * this row. Shift the whole panel up by one row while the prompt is
     * visible so the rest row clears those self-drawn lines. {@code
     * guiHeight()} is invoked exactly once per frame, to compute {@code
     * baseY}, so redirecting it moves every row uniformly.
     */
    private static final int REST_PROMPT_PANEL_SHIFT = 13; // one ROW_H

    @Redirect(
            method = "lambda$register$1",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/utils/client/betterrender/FakeGuiGraphics;guiHeight()I",
                    remap = false
            ),
            require = 0
    )
    private static int habitrain$shiftRestPromptPanel(io.wifi.utils.client.betterrender.FakeGuiGraphics graphics) {
        int height = graphics.guiHeight();
        if (EliminatedRestPromptState.isVisible()) {
            height -= REST_PROMPT_PANEL_SHIFT;
        }
        return height;
    }

    @Redirect(
            method = "lambda$register$1",
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
