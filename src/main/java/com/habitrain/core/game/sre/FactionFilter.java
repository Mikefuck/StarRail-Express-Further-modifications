package com.habitrain.core.game.sre;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

    public static FactionContext determineFaction(Player player, boolean hasActiveTasks) {
        GameMode activeMode = resolveActiveGameMode(player);
        boolean killerDualTask = isKillerDualTaskMode(activeMode, player);
        boolean hasExistingTask = hasActiveTasks;
        TaskCategory forcedCategory = null;
        boolean skipActiveTaskGuard = false;
        boolean currentIsFakeTask = false;

        if (killerDualTask) {
            if (hasExistingTask) {
                forcedCategory = BlackoutMode.BLACKOUT_GOOD;
                skipActiveTaskGuard = true;
                currentIsFakeTask = true;
            } else {
                forcedCategory = BlackoutMode.BLACKOUT_BAD;
                currentIsFakeTask = false;
            }
        } else if (activeMode instanceof BlackoutMode) {
            forcedCategory = BlackoutMode.BLACKOUT_GOOD;
            currentIsFakeTask = false;
        }

        return new FactionContext(forcedCategory, killerDualTask, hasExistingTask, currentIsFakeTask, skipActiveTaskGuard, activeMode);
    }

    public static boolean isKillerDualTaskMode(@Nullable GameMode activeMode, Player player) {
        if (!(player instanceof ServerPlayer sp)) return false;
        if (!(sp.level() instanceof ServerLevel level)) return false;
        if (activeMode == null) return false;
        if (!(activeMode instanceof BlackoutMode)) return false;
        try {
            return BlackoutRoleManager.getFaction(level, sp.getUUID())
                    == BlackoutRoleManager.Faction.BAD;
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    public static GameMode resolveActiveGameMode(Player player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }
        return GameModeRegistry.getActiveForLevel(level).orElse(null);
    }
}
