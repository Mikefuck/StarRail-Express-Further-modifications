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

    /** Resolves the active mode; ordinary tasks do not force a faction pool. */
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
