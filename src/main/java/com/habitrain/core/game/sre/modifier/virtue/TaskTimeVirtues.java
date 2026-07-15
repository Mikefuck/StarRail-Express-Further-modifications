package com.habitrain.core.game.sre.modifier.virtue;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.modifier.HabiModifiers;
import net.minecraft.world.entity.player.Player;
import org.agmas.harpymodloader.component.WorldModifierComponent;

/**
 * 耐心 / 勤勉：有效交互任务时间倍率。
 * <ul>
 *   <li>耐心 ×1.5（更慢 / 更久）</li>
 *   <li>勤勉 ×0.7（更快）</li>
 * </ul>
 * 仅作用于交互进度任务（有 duration 的 timer 类任务），不影响纯点击/进食/场景触发等。
 * 实际注入见 {@link com.habitrain.core.game.sre.mixin.TaskInteractTimeMixin}。
 */
public final class TaskTimeVirtues {
    public static final double PATIENCE_MULT = 1.5;
    public static final double DILIGENCE_MULT = 0.7;

    private TaskTimeVirtues() {}

    public static void init() {
        HabiTrainCore.LOGGER.info("[TaskTimeVirtues] patience×{} diligence×{} (via TaskInteractTimeMixin)",
                PATIENCE_MULT, DILIGENCE_MULT);
    }

    public static boolean hasPatience(Player player) {
        return isModifier(player, HabiModifiers.PATIENCE);
    }

    public static boolean hasDiligence(Player player) {
        return isModifier(player, HabiModifiers.DILIGENCE);
    }

    /**
     * @return multiplier to apply to interactive duration, or 1.0 if neither virtue.
     *         Patience and diligence are hard-exclusive; if both somehow present, diligence wins
     *         (faster) then patience is ignored — but mutex should prevent this.
     */
    public static double interactTimeMultiplier(Player player) {
        if (player == null) return 1.0;
        if (hasDiligence(player)) return DILIGENCE_MULT;
        if (hasPatience(player)) return PATIENCE_MULT;
        return 1.0;
    }

    /** Scale a positive duration by virtue mult, floored at 1 tick. */
    public static int scaleDuration(Player player, int baseDuration) {
        if (baseDuration <= 0) return baseDuration;
        double mult = interactTimeMultiplier(player);
        if (mult == 1.0) return baseDuration;
        return Math.max(1, (int) Math.round(baseDuration * mult));
    }

    private static boolean isModifier(Player player, org.agmas.harpymodloader.modifiers.SREModifier mod) {
        if (player == null || mod == null) return false;
        try {
            WorldModifierComponent wmc = WorldModifierComponent.getInstance(player);
            return wmc != null && wmc.isModifier(player, mod);
        } catch (Throwable t) {
            return false;
        }
    }
}
