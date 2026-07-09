package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import com.habitrain.core.game.sre.*;
import com.habitrain.core.task.TaskManager;
import io.wifi.starrailexpress.cca.SREPlayerMoodComponent;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;

@Mixin(SREPlayerTaskComponent.class)
public abstract class GenerateTaskMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("GenerateTaskMixin");
    private static final Set<String> BUILTIN_SRE_TASK_IDS = Set.of(
            "sleep", "raed_book", "eat", "drink", "exercise", "meditate",
            "bathe", "chair", "note_block", "toilet", "be_alone",
            "breathe", "light_stove", "clean_dust", "transport",
            "pray", "prune_bush", "harvest_crop"
    );

    @Shadow(remap = false) private Player player;
    @Shadow(remap = false) public Map<SREPlayerTaskComponent.Task, SREPlayerTaskComponent.TrainTask> tasks;
    @Shadow(remap = false) public Map<SREPlayerTaskComponent.Task, Integer> timesGotten;
    @Shadow(remap = false) public SREPlayerMoodComponent playerMoodComponent;

    @Shadow(remap = false)
    private Set<String> getDisabledTasks() {
        throw new AssertionError("Shadowed");
    }

    @Shadow(remap = false)
    private Set<String> getEnabledSceneTasks() {
        throw new AssertionError("Shadowed");
    }

    @Shadow(remap = false)
    @Nullable
    private SREPlayerTaskComponent.TrainTask createTaskInstance(SREPlayerTaskComponent.Task taskType) {
        throw new AssertionError("Shadowed");
    }

    @Inject(method = "generateTaskInternal", at = @At("HEAD"), cancellable = true, remap = false)
    private void onGenerateTaskInternal(CallbackInfoReturnable<SREPlayerTaskComponent.TrainTask> cir) {
        LOGGER.debug("[HabiDebug] ===== genTask CALLED! tasks.size={}, timesGotten={} =====",
                tasks.size(), timesGotten.size());

        float currentMood = (playerMoodComponent != null) ? playerMoodComponent.getMood() : 1f;
        Set<String> disabledTasks = getDisabledTasks();
        TaskManager mgr = TaskManager.getInstance();
        String mapName = mgr.getCurrentMapName(player);
        var currentCategory = mgr.getCurrentGameModeCategory(player);

        FactionFilter.FactionContext ctx = FactionFilter.determineFaction(player, !this.tasks.isEmpty());

        // Blackout restore-power guard
        if (ctx.activeMode() instanceof BlackoutMode
                && !ctx.killerDualTask() && player instanceof ServerPlayer sp
                && sp.level() instanceof ServerLevel level) {
            TaskInstance activeTask = mgr.getActiveTask(sp.getUUID());
            if (activeTask != null && "habitrain_core:restore_power".equals(activeTask.getFullId())
                    && !activeTask.isFulfilled()) {
                var phase = BlackoutTimerSystem.getPhase(level);
                if (phase == BlackoutTimerSystem.Phase.FIRST_BLACKOUT
                        || phase == BlackoutTimerSystem.Phase.SECOND_BLACKOUT) {
                    LOGGER.info("[HabiDebug] Player has active restore_power task during blackout, skipping dispatch");
                    cir.setReturnValue(null);
                    return;
                }
            }
        }

        LOGGER.debug("[HabiDebug] mapName='{}', currentMood={}, disabledTasks={}, category={}, killerDual={}, parallel={}",
                mapName, currentMood, disabledTasks, currentCategory,
                ctx.killerDualTask(), ctx.isParallelCall());

        List<Map.Entry<Object, Float>> weightEntries = new ArrayList<>();
        float total = 0f;

        if (!ctx.killerDualTask()) {
            total += TaskWeightCalculator.addOriginalTasks(
                    weightEntries, currentMood, disabledTasks, mapName, mgr,
                    ctx.activeMode(), player, tasks, timesGotten,
                    BUILTIN_SRE_TASK_IDS, getEnabledSceneTasks());
        }
        total += DlcTaskPoolBuilder.addDlcTasks(
                weightEntries, mgr, mapName, currentCategory, disabledTasks,
                ctx.activeMode(), ctx.forcedCategory(), ctx.skipActiveTaskGuard(),
                ctx.currentIsFakeTask(), player, BUILTIN_SRE_TASK_IDS);

        LOGGER.debug("[HabiDebug] Flat pool built: {} entries, total weight={}",
                weightEntries.size(), String.format("%.2f", total));

        boolean isFakeTask = ctx.currentIsFakeTask();
        cir.setReturnValue(TaskSelector.weightedSelect(
                weightEntries, total, player,
                this::createTaskInstance,
                def -> DlcTaskTracker.createAndTrackDlcTask(def, isFakeTask, player)));
    }
}
