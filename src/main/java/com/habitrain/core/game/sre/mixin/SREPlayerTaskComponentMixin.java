package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.ItemReclaimHelper;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.sre.PerPlayerTaskTicker;
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
public abstract class SREPlayerTaskComponentMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("SREPlayerTaskComponentMixin");

    // S6-010 @Shadow evaluation:
    //   player                → getPlayer() method shadow (public API, least fragile)
    //   parallelTaskGenerated → public boolean field; no public getter in SRE, kept as @Shadow
    //   playerMoodComponent   → public field; no public getter in SRE, kept as @Shadow
    //   tasks                 → public Map field; no public getter in SRE, kept as @Shadow
    //   generateParallelTask() → public API method, least fragile form of @Shadow
    // If SRE renames these public members in a future version, the mixin config should
    // set required=false to degrade gracefully instead of crashing on load.

    @Shadow(remap = false)
    public abstract Player getPlayer();

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
        Player p = getPlayer();
        if (p != null) {
            TaskManager mgr = TaskManager.getInstance();
            TaskInstance oldTask = mgr.getActiveTask(p.getUUID());
            if (oldTask != null) {
                LOGGER.debug("[HabiDebug] init() called - clearing activeCustomTask {} for player {}",
                        oldTask.getFullId(), p.getName().getString());
                // 先回收发放的物理道具（任务被新角色 init 取消时）
                ItemReclaimHelper.reclaimForTask(p, oldTask);
                mgr.removeActiveTask(p.getUUID());

                if (p instanceof ServerPlayer sp) {
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
        Player p = getPlayer();
        if (p != null) {
            TaskManager mgr = TaskManager.getInstance();
            TaskInstance oldTask = mgr.getActiveTask(p.getUUID());
            if (oldTask != null) {
                // 先回收发放的物理道具（任务被 clear 取消时）
                ItemReclaimHelper.reclaimForTask(p, oldTask);
            }
            mgr.removeActiveTask(p.getUUID());
            LOGGER.debug("[HabiDebug] clear() called - removed activeCustomTask for player {}",
                    p.getName().getString());

            if (p instanceof ServerPlayer sp) {
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
        Player player = getPlayer();
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
                        && PerPlayerTaskTicker.mgrHasActiveDlcTask(sp)) {
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

        PerPlayerTaskTicker.tick(player);
    }

}
