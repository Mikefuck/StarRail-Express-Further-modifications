package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.sins.component.EnvyComponent;
import com.habitrain.core.game.sre.role.sins.component.GluttonyComponent;
import com.habitrain.core.game.sre.role.sins.component.GreedComponent;
import com.habitrain.core.game.sre.role.sins.component.LustComponent;
import com.habitrain.core.game.sre.role.sins.component.PrideComponent;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import com.habitrain.core.game.sre.role.sins.component.WrathComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.AllowPlayerDeathWithKiller;
import io.wifi.starrailexpress.event.OnPlayerDeathWithKiller;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.harpymodloader.events.ModdedRoleAssigned;

/**
 * 七宗罪职业事件：分配时初始化 CCA 状态机；傲慢免疫/破防钩子。
 */
public final class SevenSinEvents {
    private SevenSinEvents() {}

    private static boolean registered;

    public static void init() {
        if (registered) return;
        registered = true;

        ModdedRoleAssigned.EVENT.register((player, role) -> {
            if (player == null || role == null) return;
            if (!(player instanceof ServerPlayer sp)) return;
            ResourceLocation id = role.identifier();
            if (SevenSins.PRIDE_ID.equals(id)) {
                PrideComponent.KEY.get(sp).init();
            } else if (SevenSins.ENVY_ID.equals(id)) {
                EnvyComponent.KEY.get(sp).init();
            } else if (SevenSins.WRATH_ID.equals(id)) {
                WrathComponent.KEY.get(sp).init();
            } else if (SevenSins.GREED_ID.equals(id)) {
                GreedComponent.KEY.get(sp).init();
            } else if (SevenSins.GLUTTONY_ID.equals(id)) {
                GluttonyComponent.KEY.get(sp).init();
            } else if (SevenSins.LUST_ID.equals(id)) {
                LustComponent.KEY.get(sp).init();
            } else if (SevenSins.SLOTH_ID.equals(id)) {
                SlothComponent.KEY.get(sp).init();
            }
        });

        // Pride aura: cancel conventional weapon deaths while immune (non-force paths).
        AllowPlayerDeathWithKiller.EVENT.register((victim, killer, deathReason) -> {
            if (!(victim instanceof ServerPlayer dead)) return true;
            if (!(dead.level() instanceof ServerLevel level)) return true;

            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null || SevenSins.PRIDE == null || !game.isRole(dead, SevenSins.PRIDE)) {
                return true;
            }
            if (SinDeathReasons.isForcePath(deathReason)) {
                return true;
            }
            if (!SinDeathReasons.isConventionalWeapon(deathReason)) {
                return true;
            }
            if (!PrideComponent.isPrideWeaponImmune(dead)) {
                return true;
            }

            dead.setHealth(dead.getMaxHealth());
            dead.displayClientMessage(Component.literal("§6[傲慢] 人群加持下，常规武器无法伤你。"), true);
            HabiTrainCore.LOGGER.debug("[Pride] cancelled conventional death for {} reason={}",
                    dead.getGameProfile().getName(), deathReason);
            return false;
        });

        // Pride kill → 5s break immunity window.
        OnPlayerDeathWithKiller.EVENT.register((victim, killer, deathReason) -> {
            if (!(killer instanceof ServerPlayer killerSp)) return;
            if (!(killerSp.level() instanceof ServerLevel level)) return;

            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null || SevenSins.PRIDE == null || !game.isRole(killerSp, SevenSins.PRIDE)) {
                return;
            }
            try {
                PrideComponent.KEY.get(killerSp).onPrideKill(level);
                killerSp.displayClientMessage(
                        Component.literal("§c[傲慢] 击杀破防 " + PrideComponent.BREAK_IMMUNE_SECONDS + " 秒！"),
                        true
                );
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Pride] onPrideKill failed", t);
            }
        });

        HabiTrainCore.LOGGER.info("[SevenSinEvents] pride death/kill hooks registered");
    }
}
