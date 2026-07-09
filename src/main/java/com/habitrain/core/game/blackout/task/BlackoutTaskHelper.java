package com.habitrain.core.game.blackout.task;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import io.wifi.starrailexpress.cca.SREPlayerMoodComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

final class BlackoutTaskHelper {

    static final int DEFAULT_GOLD_REWARD = 25;
    static final float DEFAULT_EMOTION_REWARD = 0.5f;

    private BlackoutTaskHelper() {
    }

    static void grantRewards(ServerPlayer player, String taskFullId, int defaultGold, float defaultEmotion) {
        TaskConfigEntry config = ConfigManager.getInstance().getTaskConfig(taskFullId);
        int gold = (config != null && config.hasGoldReward) ? config.goldReward : defaultGold;
        float emotion = (config != null && config.hasEmotionReward)
                ? config.emotionReward : defaultEmotion;
        boolean goldSuccess = false;
        boolean moodSuccess = false;
        try {
            var shop = SREPlayerShopComponent.KEY.get(player);
            if (shop != null) {
                shop.addToBalance(gold);
                goldSuccess = true;
            } else {
                HabiTrainCore.LOGGER.warn("任务 {} 发放金币失败: SREPlayerShopComponent 为 null (player={})",
                        taskFullId, player.getName().getString());
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("任务 {} 发放金币奖励失败", taskFullId, t);
        }
        try {
            var mood = SREPlayerMoodComponent.KEY.get(player);
            if (mood != null) {
                mood.addMood(emotion);
                moodSuccess = true;
            } else {
                HabiTrainCore.LOGGER.warn("任务 {} 发放情绪奖励失败: SREPlayerMoodComponent 为 null (player={})",
                        taskFullId, player.getName().getString());
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("任务 {} 发放情绪奖励失败", taskFullId, t);
        }
        if (!goldSuccess || !moodSuccess) {
            HabiTrainCore.LOGGER.warn("任务 {} 奖励发放不完整: gold={} mood={} (player={})",
                    taskFullId, goldSuccess, moodSuccess, player.getName().getString());
        }
    }

    static void grantRewards(ServerPlayer player, String taskFullId) {
        grantRewards(player, taskFullId, DEFAULT_GOLD_REWARD, DEFAULT_EMOTION_REWARD);
    }

    /**
     * 根据任务声明的 TimeImpact 应用停电计时器变更。
     * 替代各任务 onComplete 里硬编码的 delayMaintenanceOrCountdown / reduceTime 等调用。
     *
     * 维护约定：任务注册时通过 .timeImpact(axis, delta) 声明时间影响，
     * onComplete 调用本方法即可。改 delta 自动影响自适应刷新概率曲线。
     *
     * @param level  服务端世界
     * @param fullId 任务完整 ID（用于查 TaskDefinition）
     */
    static void applyTimeImpact(ServerLevel level, String fullId) {
        if (level == null || fullId == null) return;
        TaskDefinition def = com.habitrain.core.api.TaskRegistry.get(fullId);
        if (def == null || def.getTimeImpact() == null) return;
        TaskDefinition.TimeImpact impact = def.getTimeImpact();
        int delta = impact.deltaSeconds();
        switch (impact.axis()) {
            case MAINTENANCE_OR_COUNTDOWN -> {
                if (delta > 0) BlackoutTimerSystem.delayMaintenanceOrCountdown(level, delta);
                else if (delta < 0) BlackoutTimerSystem.reduceMaintenanceOrCountdown(level, -delta);
            }
            case TOTAL_TIME -> {
                if (delta > 0) BlackoutTimerSystem.addTime(level, delta);
                else if (delta < 0) BlackoutTimerSystem.reduceTime(level, -delta);
            }
            case RESTORE_POWER -> BlackoutTimerSystem.restorePower(level);
            case TRANSIENT -> BlackoutTimerSystem.triggerTransientBlackout(level);
        }
    }
}
