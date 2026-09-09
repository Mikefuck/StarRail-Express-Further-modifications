package com.habitrain.core.client;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.component.LustComponent;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import com.habitrain.core.game.sre.role.sins.component.WrathComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.client.CommonInstinctEvents;
import io.wifi.starrailexpress.util.TrueFalseAndCustomResult;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * 客户端职业钩子：色欲本能高亮、暴怒状态 HUD 与沉睡黑屏。
 */
@Environment(EnvType.CLIENT)
public final class HabiRoleClientHooks {
    private HabiRoleClientHooks() {}

    private static boolean registered;

    /** Soft pink for true lovers (phase 1 read-only). */
    private static final int LOVER_HIGHLIGHT = 0xFF66AA;
    /** Magenta for desire-marked survivors (phase 2). */
    private static final int DESIRE_HIGHLIGHT = 0xCC3399;
    public static void init() {
        if (registered) return;
        registered = true;
        CommonInstinctEvents.ALIVE_COMMON_AFTER_EVENT.register(HabiRoleClientHooks::sinInstinctHighlight);
        // WrathDoorAttackMixin lets this callback run past the adventure restriction.
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide && WrathComponent.canPryDoorWithBat(player, pos)) {
                return net.minecraft.world.InteractionResult.SUCCESS;
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> {
            renderWrathHud(graphics);
            renderInducedSleepOverlay(graphics);
        });
        HabiTrainCore.LOGGER.info("[HabiRoleClientHooks] sin highlights, Wrath HUD and induced-sleep overlay registered");
    }

    private static TrueFalseAndCustomResult<Integer> sinInstinctHighlight(
            LocalPlayer viewer, Entity entity, boolean spectator) {
        if (viewer == null || entity == null) {
            return TrueFalseAndCustomResult.pass();
        }
        if (!(entity instanceof Player target)) {
            return TrueFalseAndCustomResult.pass();
        }
        if (target.getUUID().equals(viewer.getUUID())) {
            return TrueFalseAndCustomResult.pass();
        }

        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(viewer.level());
            if (game == null) {
                return TrueFalseAndCustomResult.pass();
            }
            if (HabiRoles.isHabiRole(viewer, SevenSins.LUST)) {
                LustComponent lust = LustComponent.KEY.get(viewer);
                if (lust != null && lust.isDesireMarked(target.getUUID())) {
                    return TrueFalseAndCustomResult.custom(DESIRE_HIGHLIGHT);
                }
                if (lust != null && lust.isKnownLover(target.getUUID())) {
                    return TrueFalseAndCustomResult.custom(LOVER_HIGHLIGHT);
                }
            }
        } catch (Throwable t) {
            // Client highlight is best-effort.
        }
        return TrueFalseAndCustomResult.pass();
    }

    private static void renderWrathHud(GuiGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || player.isSpectator() || client.options.hideGui) return;
        if (!HabiRoles.isHabiRole(player, SevenSins.WRATH)) return;

        try {
            WrathComponent wrath = WrathComponent.KEY.get(player);
            if (wrath == null) return;

            Component rage = Component.translatable(
                    "hud.habitrain_core.sin_wrath.rage",
                    wrath.getRage(),
                    wrath.getEffectStacks());
            Component progress = wrath.isBerserkActive()
                    ? Component.translatable(
                            "hud.habitrain_core.sin_wrath.berserk",
                            wrath.getBerserkTriggers())
                    : Component.translatable(
                            "hud.habitrain_core.sin_wrath.progress",
                            wrath.getBerserkProgress(),
                            wrath.getBerserkThreshold());

            int right = graphics.guiWidth() - 12;
            int bottom = graphics.guiHeight() - 20;
            graphics.drawString(client.font, rage,
                    right - client.font.width(rage), bottom - client.font.lineHeight - 4,
                    0xFFE052, true);
            graphics.drawString(client.font, progress,
                    right - client.font.width(progress), bottom,
                    wrath.isBerserkActive() ? 0xFF3B30 : 0xD8D8D8, true);
        } catch (Throwable ignored) {
            // Client HUD is best-effort during disconnect and role resync.
        }
    }

    private static void renderInducedSleepOverlay(GuiGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || !SlothComponent.isSleepingSloth(player)) return;
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0xFF000000);
    }
}
