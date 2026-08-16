package com.habitrain.core.game.sre;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.blackout.BlackoutExclusiveTasks;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.util.SubtitleNotifier;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DlcTaskTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("DlcTaskTracker");

    private DlcTaskTracker() {}

    /**
     * Creates a DLC task instance, tracks assignment counts, manages blackout rotation flag,
     * and returns the wrapped TrainTask.
     *
     * @param def        the DLC task definition
     * @param isFakeTask whether this is a fake task (parallel call from killer dual-task)
     * @param player     the player
     * @return wrapped TrainTask
     */
    public static SREPlayerTaskComponent.TrainTask createAndTrackDlcTask(
            TaskDefinition def, boolean isFakeTask, Player player) {
        TaskManager mgr = TaskManager.getInstance();
        LOGGER.debug("[HabiDebug] createAndTrackDlcTask: {} for {} (fake={})",
                def.getFullId(), player.getName().getString(), isFakeTask);
        TaskInstance instance = new TaskInstance(def);
        instance.setDimension(player.level().dimension());
        def.onAssign(player, instance);

        mgr.incrementDlcTaskCount(player.getUUID(), def.getFullId());

        if (isFakeTask) {
            mgr.setFakeTask(player.getUUID(), instance);
            SREPlayerTaskComponent.Task fakeSlot = SREPlayerTaskComponent.Task.PRAY;
            if (player instanceof ServerPlayer sp) {
                ActiveTaskPayload.sendToPlayer(sp, def.getFullId(), true);
                sendNewTaskTop(sp, def);
            }
            return new SRETrainTaskWrapper(instance, fakeSlot);
        } else {
            if (player.level() instanceof ServerLevel level) {
                GameMode activeMode = GameModeRegistry.getActiveForLevel(level).orElse(null);
                if (activeMode instanceof BlackoutMode
                        && BlackoutMode.BLACKOUT_GOOD.equals(def.getCategory())
                        && (BlackoutExclusiveTasks.isSupply(def.getFullId())
                        || BlackoutExclusiveTasks.isDaily(def.getFullId()))) {
                    boolean wasDaily = mgr.isBlackoutNextDailyPool(player.getUUID());
                    mgr.setBlackoutNextDailyPool(player.getUUID(), !wasDaily);
                    LOGGER.info("[HabiDebug] Blackout rotation flag toggled: {} -> {} for player {}",
                            wasDaily ? "DAILY" : "SUPPLY", !wasDaily ? "DAILY" : "SUPPLY",
                            player.getName().getString());
                }
            }

            mgr.setActiveTask(player.getUUID(), instance);
            if (player instanceof ServerPlayer sp) {
                ActiveTaskPayload.sendToPlayer(sp, def.getFullId());
                sendNewTaskTop(sp, def);
            }
            return new SRETrainTaskWrapper(instance);
        }
    }

    /** 对齐 SRE 原版 subtitle.task.new 的 TOP 报幕，避免只闪中间 ActiveTask。 */
    private static void sendNewTaskTop(ServerPlayer player, TaskDefinition def) {
        String name = def.getDisplayName();
        Component title = Component.literal(name != null && !name.isEmpty() ? name : def.getFullId());
        Component sub = Component.translatable("subtitle.task.new");
        SubtitleNotifier.sendTop(player, title, sub, 75);
    }
}
