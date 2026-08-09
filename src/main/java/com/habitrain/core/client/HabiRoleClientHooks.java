package com.habitrain.core.client;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.component.EnvyComponent;
import com.habitrain.core.game.sre.role.sins.component.LustComponent;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.client.CommonInstinctEvents;
import io.wifi.starrailexpress.util.TrueFalseAndCustomResult;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * 客户端职业钩子：色欲/懒惰/嫉妒本能高亮。
 */
@Environment(EnvType.CLIENT)
public final class HabiRoleClientHooks {
    private HabiRoleClientHooks() {}

    private static boolean registered;

    /** Soft pink for true lovers (phase 1 read-only). */
    private static final int LOVER_HIGHLIGHT = 0xFF66AA;
    /** Magenta for desire-marked survivors (phase 2). */
    private static final int DESIRE_HIGHLIGHT = 0xCC3399;
    private static final int SLOTH_ATTACKER_HIGHLIGHT = 0xE53935;
    /** Envy mark: target richer than self. */
    private static final int ENVY_RICHER_HIGHLIGHT = 0x9C27B0;
    /** Envy mark: target poorer than self. */
    private static final int ENVY_POORER_HIGHLIGHT = 0x2196F3;

    public static void init() {
        if (registered) return;
        registered = true;
        CommonInstinctEvents.ALIVE_COMMON_AFTER_EVENT.register(HabiRoleClientHooks::sinInstinctHighlight);
        HabiTrainCore.LOGGER.info("[HabiRoleClientHooks] sin highlights registered");
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
            if (SevenSins.ENVY != null && game.isRole(viewer, SevenSins.ENVY)) {
                EnvyComponent envy = EnvyComponent.KEY.get(viewer);
                // Only the current mark gets money-color outline.
                if (envy != null && envy.isMark(target)) {
                    int selfBal = envy.getSelfBalance();
                    int targetBal = envy.getKnownBalance(target.getUUID());
                    if (targetBal > selfBal) {
                        return TrueFalseAndCustomResult.custom(ENVY_RICHER_HIGHLIGHT);
                    }
                    if (targetBal < selfBal) {
                        return TrueFalseAndCustomResult.custom(ENVY_POORER_HIGHLIGHT);
                    }
                    // Equal money → default killer instinct color
                    return TrueFalseAndCustomResult.pass();
                }
            }
            if (SevenSins.SLOTH != null && game.isRole(viewer, SevenSins.SLOTH)) {
                SlothComponent sloth = SlothComponent.KEY.get(viewer);
                if (sloth != null && sloth.getAttackers().contains(target.getUUID())) {
                    return TrueFalseAndCustomResult.custom(SLOTH_ATTACKER_HIGHLIGHT);
                }
            }
            if (SevenSins.LUST != null && game.isRole(viewer, SevenSins.LUST)) {
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
}
