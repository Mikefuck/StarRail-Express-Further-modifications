package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import com.habitrain.core.game.sre.*;
import com.habitrain.core.task.TaskManager;
import io.wifi.starrailexpress.cca.SREPlayerMoodComponent;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
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

@Mixin(value = SREPlayerTaskComponent.class, remap = false)
public abstract class GenerateTaskMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("GenerateTaskMixin");
    /**
     * 原版 SRE 任务 ID 清单 —— 直接派生自 {@link com.habitrain.core.game.sre.SreTaskMirrors}
     * （单一真相表），不再手工维护。
     *
     * <p>它是<b>第二道防线</b>：主判据仍是
     * {@link com.habitrain.core.api.TaskDefinition#isPoolEligible()}
     * （{@code SREGameModeBase} 登记的空壳镜像都标了 {@code poolEligible(false)}）。
     * 保留本清单是为了兜住「下游扩展 mod 注册了同名任务却忘了打标」——
     * 空壳没有 onTick / completionChecker，一旦进池就是「分配即完成」
     * （{@code outside} 曾漏列，出过这个事故）。
     */
    private static final Set<String> BUILTIN_SRE_TASK_IDS =
            com.habitrain.core.game.sre.SreTaskMirrors.ids();

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
        if (habitrain$forceSleep(cir)) return;
        LOGGER.debug("[HabiDebug] ===== genTask CALLED! tasks.size={}, timesGotten={} =====",
                tasks.size(), timesGotten.size());

        float currentMood = (playerMoodComponent != null) ? playerMoodComponent.getMood() : 1f;
        Set<String> disabledTasks = getDisabledTasks();
        TaskManager mgr = TaskManager.getInstance();
        String mapName = mgr.getCurrentMapName(player);
        var currentCategory = mgr.getCurrentGameModeCategory(player);
        var activeMode = resolveActiveMode();

        LOGGER.debug("[HabiDebug] mapName='{}', currentMood={}, disabledTasks={}, category={}, activeMode={}",
                mapName, currentMood, disabledTasks, currentCategory,
                activeMode != null ? activeMode.getId() : "(none)");

        List<Map.Entry<Object, Float>> weightEntries = new ArrayList<>();
        float total = 0f;

        total += TaskWeightCalculator.addOriginalTasks(
                weightEntries, currentMood, disabledTasks, mapName, mgr,
                activeMode, player, tasks, timesGotten,
                BUILTIN_SRE_TASK_IDS, getEnabledSceneTasks());
        total += DlcTaskPoolBuilder.addDlcTasks(
                weightEntries, mgr, mapName, currentCategory, disabledTasks,
                activeMode, player, BUILTIN_SRE_TASK_IDS);

        LOGGER.debug("[HabiDebug] Flat pool built: {} entries, total weight={}",
                weightEntries.size(), String.format("%.2f", total));

        SREPlayerTaskComponent.TrainTask selected = TaskSelector.weightedSelect(
                weightEntries, total, player,
                this::createTaskInstance,
                def -> DlcTaskTracker.createAndTrackDlcTask(def, player));

        cir.setReturnValue(selected);
    }

    /**
     * 当前维度的活跃 GameMode（可为空）。
     * <p>原 {@code FactionFilter} 只负责这一件事；其余「阵营/双任务/强制分类/跳过守卫」
     * 分区已在 2.0.10 连同杀手双任务机制彻底删除。
     */
    @org.spongepowered.asm.mixin.Unique
    private com.habitrain.core.api.GameMode resolveActiveMode() {
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return null;
        }
        return com.habitrain.core.api.GameModeRegistry.getActiveForLevel(level).orElse(null);
    }

    // These public paths may bypass generateTaskInternal (e.g. the manic modifier).
    @Inject(method = {"generateTask", "generateParallelTask"}, at = @At("HEAD"), cancellable = true)
    private void habitrain$guardAllGeneration(CallbackInfoReturnable<SREPlayerTaskComponent.TrainTask> cir) {
        habitrain$forceSleep(cir);
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean habitrain$forceSleep(CallbackInfoReturnable<SREPlayerTaskComponent.TrainTask> cir) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !SlothComponent.KEY.get(serverPlayer).hasForcedSleepTask()) return false;
        // Never replace an existing sleep task: that would reset the time already slept.
        // SlothComponent replaces existing tasks immediately and maintains this lock until completion.
        cir.setReturnValue(tasks.isEmpty()
                ? new SREPlayerTaskComponent.SleepTask(io.wifi.starrailexpress.game.GameConstants.SLEEP_TASK_DURATION)
                : null);
        return true;
    }
}
