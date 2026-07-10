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
    /**
     * IDs of hollow SRE mirror registrations in {@code SREGameModeBase#registerBuiltin}.
     * Must stay in sync with that list so they never enter the DLC weighted pool
     * (empty shells have no onTick / completionChecker and would complete instantly).
     * Includes {@code outside} (displayName 外出) — previously missing, which caused
     * assign-and-complete for 外出.
     */
    private static final Set<String> BUILTIN_SRE_TASK_IDS = Set.of(
            "sleep", "raed_book", "eat", "drink", "exercise", "meditate",
            "bathe", "chair", "note_block", "toilet", "be_alone",
            "breathe", "outside", "vending_machine",
            "light_stove", "clean_dust", "transport",
            "pray", "prune_bush", "harvest_crop",
            "repair_wire", "repair_panel"
    );

    /** Throttle empty-pool WARN logs per player (ms). */
    private static final Map<UUID, Long> EMPTY_POOL_WARN_AT = new HashMap<>();
    private static final long EMPTY_POOL_WARN_COOLDOWN_MS = 15_000L;

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
                ctx.killerDualTask(), ctx.hasExistingTask());

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

        if (weightEntries.isEmpty() || total <= 0f) {
            maybeWarnEmptyPool(ctx, mapName, disabledTasks);
        }

        boolean isFakeTask = ctx.currentIsFakeTask();
        SREPlayerTaskComponent.TrainTask selected = TaskSelector.weightedSelect(
                weightEntries, total, player,
                this::createTaskInstance,
                def -> DlcTaskTracker.createAndTrackDlcTask(def, isFakeTask, player));

        if (selected == null && ctx.killerDualTask() && this.tasks.isEmpty()) {
            LOGGER.info("[HabiDebug] Killer gen returned null (tasks empty, poolEntries={}, total={}) player={}",
                    weightEntries.size(), String.format("%.2f", total), player.getName().getString());
        }

        cir.setReturnValue(selected);
    }

    private void maybeWarnEmptyPool(FactionFilter.FactionContext ctx, String mapName, Set<String> disabledTasks) {
        if (!(player instanceof ServerPlayer sp)) return;
        // Only interesting for blackout killers waiting on BAD pool, or dual fake GOOD.
        if (!(ctx.activeMode() instanceof BlackoutMode) || !ctx.killerDualTask()) return;

        long now = System.currentTimeMillis();
        Long last = EMPTY_POOL_WARN_AT.get(sp.getUUID());
        if (last != null && now - last < EMPTY_POOL_WARN_COOLDOWN_MS) return;
        EMPTY_POOL_WARN_AT.put(sp.getUUID(), now);

        TaskManager mgr = TaskManager.getInstance();
        var forced = ctx.forcedCategory();
        var raw = com.habitrain.core.task.TaskPoolBuilder.getPool(
                ctx.activeMode(), mapName, forced, mgr.getCurrentGameModeCategory(player),
                player, BUILTIN_SRE_TASK_IDS);
        int canAssignFail = 0;
        int alreadyHas = 0;
        int disabled = 0;
        List<String> ids = new ArrayList<>();
        for (var def : raw) {
            ids.add(def.getFullId());
            if (mgr.hasTaskWithId(player.getUUID(), def.getFullId())) {
                alreadyHas++;
            } else if (disabledTasks.contains(def.getFullId())) {
                disabled++;
            } else if (!def.canAssign(player)) {
                canAssignFail++;
            }
        }
        LOGGER.warn(
                "[HabiDebug] Killer empty weighted pool: player={} fake={} forced={} rawCandidates={} alreadyHas={} disabled={} canAssignFail={} ids={}",
                sp.getName().getString(),
                ctx.currentIsFakeTask(),
                forced,
                raw.size(),
                alreadyHas,
                disabled,
                canAssignFail,
                ids);
    }
}
