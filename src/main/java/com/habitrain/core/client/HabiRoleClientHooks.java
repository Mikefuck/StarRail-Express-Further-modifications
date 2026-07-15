package com.habitrain.core.client;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.component.LustComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.client.OnGetInstinctHighlight;
import io.wifi.starrailexpress.util.TrueFalseAndCustomResult;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * 客户端职业钩子：色欲本能高亮真正恋人与欲望标记。
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
        OnGetInstinctHighlight.ALIVE_EVENT.register(HabiRoleClientHooks::lustInstinctHighlight);
        HabiTrainCore.LOGGER.info("[HabiRoleClientHooks] lust instinct highlight registered");
    }

    private static TrueFalseAndCustomResult<Integer> lustInstinctHighlight(
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
            if (game == null || SevenSins.LUST == null || !game.isRole(viewer, SevenSins.LUST)) {
                return TrueFalseAndCustomResult.pass();
            }
            LustComponent c = LustComponent.KEY.get(viewer);
            if (c == null) {
                return TrueFalseAndCustomResult.pass();
            }

            // Phase 2 desire marks take priority when set.
            if (c.isDesireMarked(target.getUUID())) {
                return TrueFalseAndCustomResult.custom(DESIRE_HIGHLIGHT);
            }
            // Phase 1: read-only true lover highlight.
            if (c.isKnownLover(target.getUUID())) {
                return TrueFalseAndCustomResult.custom(LOVER_HIGHLIGHT);
            }
        } catch (Throwable t) {
            // Client highlight is best-effort.
        }
        return TrueFalseAndCustomResult.pass();
    }
}
