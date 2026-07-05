package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.network.ActiveTaskPayload;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SREPlayerTaskComponent.class)
public class SREPlayerTaskComponentMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("SREPlayerTaskComponentMixin");

    @Shadow(remap = false)
    private Player player;

    @Shadow(remap = false)
    public boolean parallelTaskGenerated;

    @Shadow(remap = false)
    public io.wifi.starrailexpress.cca.SREPlayerMoodComponent playerMoodComponent;

    @Shadow(remap = false)
    public java.util.Map<SREPlayerTaskComponent.Task, SREPlayerTaskComponent.TrainTask> tasks;

    @Shadow(remap = false)
    public io.wifi.starrailexpress.cca.SREPlayerTaskComponent.TrainTask generateParallelTask() {
        throw new AssertionError("Shadowed");
    }

    @Inject(
            method = "init",
            at = @At("HEAD"),
            remap = false
    )
    private void habitrain$onInit(CallbackInfo ci) {
        if (player != null) {
            TaskManager mgr = TaskManager.getInstance();
            TaskInstance oldTask = mgr.getActiveTask(player.getUUID());
            if (oldTask != null) {
                LOGGER.debug("[HabiDebug] init() called - clearing activeCustomTask {} for player {}",
                        oldTask.getFullId(), player.getName().getString());
                mgr.removeActiveTask(player.getUUID());

                if (player instanceof ServerPlayer sp) {
                    ActiveTaskPayload.clearForPlayer(sp);
                }
            }
        }
    }

    @Inject(
            method = "clear",
            at = @At("HEAD"),
            remap = false
    )
    private void habitrain$onClear(CallbackInfo ci) {
        if (player != null) {
            TaskManager mgr = TaskManager.getInstance();
            mgr.removeActiveTask(player.getUUID());
            LOGGER.debug("[HabiDebug] clear() called - removed activeCustomTask for player {}",
                    player.getName().getString());

            if (player instanceof ServerPlayer sp) {
                ActiveTaskPayload.clearForPlayer(sp);
            }
        }
    }

    @Inject(
            method = "serverTick",
            at = @At("HEAD"),
            remap = false
    )
    private void habitrain$onServerTick(CallbackInfo ci) {
        if (player == null) return;

        // 杀手双任务强制派发：停电模式杀手一旦有了主任务(BAD)但还没有并行任务(GOOD假任务)，
        // 立即强制调用 generateParallelTask()，绕过原版的 currentTaskAge/mood 阈值条件。
        // generateParallelTask 内部会调 generateTaskInternal，被 GenerateTaskMixin 拦截后
        // 因 tasks 非空走并行分支，只从 BLACKOUT_GOOD 池选假任务。
        try {
            if (player instanceof ServerPlayer sp && sp.level() instanceof ServerLevel level) {
                GameMode activeMode = GameModeRegistry.getActiveForLevel(level).orElse(null);
                if (activeMode instanceof BlackoutMode
                        && BlackoutRoleManager.getFaction(level, sp.getUUID()) == BlackoutRoleManager.Faction.BAD
                        && !this.parallelTaskGenerated
                        && !this.tasks.isEmpty()
                        && mgrHasActiveDlcTask(sp)) {
                    LOGGER.info("[KillerDualTask] forcing parallel task for killer {} (mainTaskCount={})",
                            sp.getName().getString(), this.tasks.size());
                    SREPlayerTaskComponent.TrainTask parallel = generateParallelTask();
                    if (parallel != null) {
                        this.tasks.put(parallel.getType(), parallel);
                        this.parallelTaskGenerated = true;
                        LOGGER.info("[KillerDualTask] parallel fake task assigned: type={} name={}",
                                parallel.getType(), parallel.getName());
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.error("[KillerDualTask] failed to force parallel task", t);
        }

        TaskManager mgr = TaskManager.getInstance();
        TaskInstance customTask = mgr.getActiveTask(player.getUUID());
        TaskInstance fakeTask = mgr.getFakeTask(player.getUUID());

        if (customTask != null) {
            customTask.tick(player);
            if (customTask.isFulfilled()) {
                handleMainTaskDone(mgr, customTask);
            }
        }

        if (fakeTask != null) {
            fakeTask.tick(player);
            if (fakeTask.isFulfilled()) {
                handleFakeTaskDone(mgr, fakeTask);
            }
        }
    }

    private void handleMainTaskDone(TaskManager mgr, TaskInstance customTask) {
        if (customTask.isFailed()) {
            LOGGER.debug("[HabiDebug] Custom task {} failed, removing tracking without completion reward",
                    customTask.getFullId());
            // DLC 通过 markFailed() 手动标记失败（非 tick 超时路径）时不会触发 onFail，
            // 这里显式调用 onRemove 让 DLC 注册的清理逻辑（撤销效果/移除实体）得以执行。
            try {
                customTask.getDefinition().onRemove(player, customTask);
            } catch (Throwable t) {
                LOGGER.error("onRemove 回调执行失败: {}", customTask.getFullId(), t);
            }
            mgr.removeActiveTask(player.getUUID());
            if (player instanceof ServerPlayer sp) {
                ActiveTaskPayload.clearForPlayer(sp);
            }
        } else {
            LOGGER.debug("[HabiDebug] Custom task {} fulfilled, removing tracking", customTask.getFullId());
            if (player instanceof ServerPlayer sp) {
                mgr.handleTaskCompletion(sp, customTask);
                ActiveTaskPayload.clearForPlayer(sp);
            }
        }
    }

    private void handleFakeTaskDone(TaskManager mgr, TaskInstance fakeTask) {
        if (fakeTask.isFailed()) {
            LOGGER.info("[KillerDualTask] fake task {} failed for {}",
                    fakeTask.getFullId(), player.getName().getString());
            mgr.removeFakeTask(player.getUUID());
        } else {
            LOGGER.info("[KillerDualTask] fake task {} fulfilled for {}, granting rewards",
                    fakeTask.getFullId(), player.getName().getString());
            if (player instanceof ServerPlayer sp) {
                // 假任务完成走同一套完成处理（发金币、附属奖励钩子留空）
                mgr.handleTaskCompletion(sp, fakeTask);
            }
            mgr.removeFakeTask(player.getUUID());
        }
    }

    private static boolean mgrHasActiveDlcTask(ServerPlayer sp) {
        return TaskManager.getInstance().getActiveTask(sp.getUUID()) != null;
    }
}
