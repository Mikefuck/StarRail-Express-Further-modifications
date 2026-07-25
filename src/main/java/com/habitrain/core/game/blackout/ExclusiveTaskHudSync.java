package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.sre.SRETrainTaskWrapper;
import com.habitrain.core.game.sre.TaskEnumHelper;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * 将停电专属任务（电话购买 / 强制恢复供电）同步进 SRE 左上角任务栏。
 * <p>
 * 专属任务走 {@code TaskManager} + {@code ActiveTaskPayload}，默认不进 SRE {@code tasks} map，
 * 导致左上角空白。此处在派发时插入 {@link SRETrainTaskWrapper}，取消/完成时清掉。
 * 写入后立即 {@code sync()}，避免客户端要等下一轮 CCA 才刷新。
 */
public final class ExclusiveTaskHudSync {

    private ExclusiveTaskHudSync() {}

    /** 把专属任务塞进左上角 CUSTOM 槽；CUSTOM 不可用时静默跳过。 */
    public static void insert(ServerPlayer player, TaskInstance instance) {
        if (player == null || instance == null) return;
        if (!BlackoutExclusiveTasks.isExclusive(instance.getFullId())) return;

        var custom = TaskEnumHelper.getCustom();
        if (custom == null) {
            HabiTrainCore.LOGGER.debug("[ExclusiveHud] CUSTOM slot unavailable, skip left HUD for {}",
                    instance.getFullId());
            return;
        }
        try {
            var comp = SREPlayerTaskComponent.KEY.get(player);
            if (comp == null || comp.tasks == null) return;
            comp.tasks.put(custom, new SRETrainTaskWrapper(instance));
            // 立刻同步到客户端，避免左上角延迟刷新
            comp.sync();
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("[ExclusiveHud] insert failed for {}", instance.getFullId(), t);
        }
    }

    /**
     * 清除左上角 CUSTOM 槽中的专属 wrapper。
     * 非 wrapper / 非专属任务不碰，避免误删正常 DLC 任务。
     */
    public static void clear(ServerPlayer player) {
        if (player == null) return;
        var custom = TaskEnumHelper.getCustom();
        if (custom == null) return;
        try {
            var comp = SREPlayerTaskComponent.KEY.get(player);
            if (comp == null || comp.tasks == null) return;
            var existing = comp.tasks.get(custom);
            if (existing instanceof SRETrainTaskWrapper wrapper) {
                TaskInstance inst = wrapper.unwrap();
                if (inst != null && BlackoutExclusiveTasks.isExclusive(inst.getFullId())) {
                    comp.tasks.remove(custom);
                    comp.sync();
                }
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("[ExclusiveHud] clear failed", t);
        }
    }

    /**
     * 专属任务结束（完成/失败/取消）后：清左上角专属槽，并强制下一 tick 立刻走原版任务派发。
     * 避免 nextTaskTimer 仍在冷却导致「空白 → 再闪一次」的二次刷新。
     */
    public static void resumeVanillaDispatch(ServerPlayer player) {
        if (player == null) return;
        clear(player);
        try {
            var comp = SREPlayerTaskComponent.KEY.get(player);
            if (comp == null) return;
            // 仅在没有其它 SRE 任务时强制立刻刷新，避免打断并列任务
            if (comp.tasks == null || comp.tasks.isEmpty()) {
                comp.nextTaskTimer = 1;
                comp.sync();
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("[ExclusiveHud] resumeVanillaDispatch failed", t);
        }
    }
}
