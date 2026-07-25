package com.habitrain.core.game.sre.modifier.virtue;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.modifier.HabiModifiers;
import com.habitrain.core.game.sre.role.sins.SinRoleRules;
import io.wifi.starrailexpress.event.AllowPlayerDeathWithKiller;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.agmas.harpymodloader.component.WorldModifierComponent;

/**
 * 宽容：整局首次被好人（非 canUseKiller）击杀时取消死亡并消耗本修饰符。
 */
public final class MercyVirtue {
    private MercyVirtue() {}

    private static boolean registered;

    public static void init() {
        if (registered) return;
        registered = true;

        AllowPlayerDeathWithKiller.EVENT.register((victim, killer, deathReason) -> {
            if (!(victim instanceof ServerPlayer dead)) return true;
            if (!(killer instanceof ServerPlayer killerSp)) return true;
            if (!(dead.level() instanceof ServerLevel level)) return true;
            if (!hasMercy(dead)) return true;
            if (!SinRoleRules.isGoodAligned(level, killerSp)) return true;

            // Only cancel death after mercy is successfully consumed.
            boolean consumed = false;
            try {
                WorldModifierComponent wmc = WorldModifierComponent.getInstance(dead);
                if (wmc != null && HabiModifiers.MERCY != null) {
                    wmc.removeModifier(dead, HabiModifiers.MERCY);
                    // Confirm removal so a failed consume cannot grant free cancel.
                    consumed = !wmc.isModifier(dead, HabiModifiers.MERCY);
                }
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Mercy] removeModifier failed", t);
                consumed = false;
            }
            if (!consumed) {
                HabiTrainCore.LOGGER.warn("[Mercy] remove failed; allowing death for {}",
                        dead.getGameProfile().getName());
                return true;
            }

            dead.setHealth(dead.getMaxHealth());
            dead.displayClientMessage(Component.literal("§b[宽容] 善意护住了你一次……"), true);
            killerSp.displayClientMessage(Component.literal("§e[宽容] 对方的宽容抵消了这次击杀。"), true);
            HabiTrainCore.LOGGER.info("[Mercy] cancelled kill {} -> {} (modifier consumed)",
                    killerSp.getGameProfile().getName(), dead.getGameProfile().getName());
            return false;
        });

        HabiTrainCore.LOGGER.info("[MercyVirtue] AllowPlayerDeathWithKiller hook registered");
    }

    public static boolean hasMercy(Player player) {
        if (player == null || HabiModifiers.MERCY == null) return false;
        try {
            WorldModifierComponent wmc = WorldModifierComponent.getInstance(player);
            return wmc != null && wmc.isModifier(player, HabiModifiers.MERCY);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Killer is "good/innocent" when their role cannot use killer tools.
     * Null / missing role treated as non-innocent (do not cancel).
     */
}
