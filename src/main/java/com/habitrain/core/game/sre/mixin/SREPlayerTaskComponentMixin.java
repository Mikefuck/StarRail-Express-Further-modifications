package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.ItemReclaimHelper;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.sre.PerPlayerTaskTicker;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.network.ActiveTaskPayload;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
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
        clearTrackedTasks(getPlayer(), "init");
    }

    @Inject(
            method = "clear",
            at = @At("HEAD"),
            remap = false
    )
    private void habitrain$onClear(CallbackInfo ci) {
        clearTrackedTasks(getPlayer(), "clear");
    }

    /** 对称清理主任务 + 假任务，避免 activeFakeTasks 残留继续 tick/发奖。 */
    private static void clearTrackedTasks(Player p, String reason) {
        if (p == null) return;
        TaskManager mgr = TaskManager.getInstance();
        TaskInstance oldTask = mgr.getActiveTask(p.getUUID());
        if (oldTask != null) {
            LOGGER.debug("[HabiDebug] {}() - clearing activeCustomTask {} for player {}",
                    reason, oldTask.getFullId(), p.getName().getString());
            ItemReclaimHelper.reclaimForTask(p, oldTask);
            mgr.removeActiveTask(p.getUUID());
            if (p instanceof ServerPlayer sp) {
                ActiveTaskPayload.clearForPlayer(sp);
            }
        }
        TaskInstance fake = mgr.getFakeTask(p.getUUID());
        if (fake != null) {
            LOGGER.debug("[HabiDebug] {}() - clearing fakeTask {} for player {}",
                    reason, fake.getFullId(), p.getName().getString());
            try {
                ItemReclaimHelper.reclaimForTask(p, fake);
            } catch (Throwable t) {
                LOGGER.debug("fake task reclaim failed on {}", reason, t);
            }
            mgr.removeFakeTask(p.getUUID());
            if (p instanceof ServerPlayer sp) {
                ActiveTaskPayload.clearForPlayer(sp, true);
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

        // 停电模式任务系统独立化后，杀手双任务（假任务）机制已关闭：
        // 杀手默认只走原版 SRE 任务，坏人专属任务通过红色电话商店购买。
        // 原来的 generateParallelTask() 强制派发已移除。

        PerPlayerTaskTicker.tick(player);
    }

}
