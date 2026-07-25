package com.habitrain.core.game.sre;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskCategory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

public class FactionFilter {

    public record FactionContext(
            @Nullable TaskCategory forcedCategory,
            boolean killerDualTask,
            boolean hasExistingTask,
            boolean currentIsFakeTask,
            boolean skipActiveTaskGuard,
            @Nullable GameMode activeMode
    ) {}

    /**
     * 停电模式任务系统已独立化：专属任务仅通过红色电话商店购买，不再自动派发。
     * 因此 blackout 下默认不强制阵营类别、不进入杀手双任务模式 —— 停电局默认
     * 只走原版 SRE 任务，与「不购买时完全使用哈比列车原版任务系统」一致。
     * 付费任务由 {@link com.habitrain.core.game.blackout.shop.BlackoutTaskShopService}
     * 直接 {@code setActiveTask} 派发，绕过本过滤路径。
     */
    public static FactionContext determineFaction(Player player, boolean hasActiveTasks) {
        GameMode activeMode = resolveActiveGameMode(player);
        boolean killerDualTask = false; // 独立化后杀手双任务关闭
        boolean hasExistingTask = hasActiveTasks;
        TaskCategory forcedCategory = null;
        boolean skipActiveTaskGuard = false;
        boolean currentIsFakeTask = false;

        return new FactionContext(forcedCategory, killerDualTask, hasExistingTask, currentIsFakeTask, skipActiveTaskGuard, activeMode);
    }

    @Nullable
    public static GameMode resolveActiveGameMode(Player player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }
        return GameModeRegistry.getActiveForLevel(level).orElse(null);
    }
}
