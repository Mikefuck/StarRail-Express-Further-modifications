package com.habitrain.core.game.sre.modifier.virtue;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.modifier.HabiModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.agmas.harpymodloader.component.WorldModifierComponent;

/**
 * 谦卑：完成任务时，12 格内其他玩家 actionbar 显示「谢谢」（不全服广播）。
 * 覆盖 SRE 原版任务完成路径与 habitrain 自定义任务完成路径。
 */
public final class HumilityVirtue {
    public static final double RANGE = 12.0;

    private HumilityVirtue() {}

    public static void init() {
        // Hooks are invoked from mixins / TaskManager — no fabric event registration needed.
        HabiTrainCore.LOGGER.info("[HumilityVirtue] ready (task-complete nearby thanks)");
    }

    public static boolean hasHumility(Player player) {
        if (player == null || HabiModifiers.HUMILITY == null) return false;
        try {
            WorldModifierComponent wmc = WorldModifierComponent.getInstance(player);
            return wmc != null && wmc.isModifier(player, HabiModifiers.HUMILITY);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Called when {@code completer} finishes a task. Nearby players get actionbar 谢谢.
     */
    public static void onTaskComplete(ServerPlayer completer) {
        if (completer == null || completer.level().isClientSide) return;
        if (!hasHumility(completer)) return;
        if (!(completer.level() instanceof ServerLevel level)) return;

        double rangeSq = RANGE * RANGE;
        for (ServerPlayer other : level.players()) {
            if (other == completer) continue;
            if (!other.isAlive() || other.isSpectator()) continue;
            if (other.distanceToSqr(completer) > rangeSq) continue;
            other.displayClientMessage(Component.literal("谢谢"), true);
        }
        HabiTrainCore.LOGGER.debug("[Humility] {} completed task — nearby thanks",
                completer.getGameProfile().getName());
    }
}
