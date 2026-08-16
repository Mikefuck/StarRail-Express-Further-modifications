package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.component.MimeKillerComponent;
import com.habitrain.core.role.behavior.RoleEventDispatcher;
import io.wifi.starrailexpress.api.RoleMethodDispatcher;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREPlayerMoodComponent;
import io.wifi.starrailexpress.cca.SREPlayerProgressionComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.game.GameConstants;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RoleMethodDispatcher.class)
public class RoleMethodDispatcherMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleMethodDispatcherMixin");

    /** 与 SREConfig.civilianTaskReward 默认值对齐；运行时优先反射读配置。 */
    private static final int FALLBACK_CIVILIAN_TASK_REWARD = 50;

    /** 记录本次 callOnFinishQuest 是否被 HEAD 接管（已发自定义金 + cancel 原方法）。
     *  TAIL 据此决定是否发自定义情绪：仅 HEAD 接管时才发，避免 progression==null 路径
     *  与 SRE 原版情绪双发（P2-28）。 */
    private static final ThreadLocal<Boolean> headHandled = ThreadLocal.withInitial(() -> false);

    @Shadow(remap = false)
    private static SRERole getCurrentRole(Player player) {
        throw new AssertionError("Shadowed");
    }

    private static TaskConfigEntry findConfigForQuest(String quest) {
        if (quest == null) return null;

        String fullId = quest.contains(":") ? quest : "habitrain_core:" + quest;
        TaskConfigEntry config = ConfigManager.getInstance().getTaskConfig(fullId);

        if (config == null) {
            for (TaskDefinition def : TaskRegistry.getAll()) {
                if (def.getTaskId().equalsIgnoreCase(quest) || def.getDisplayName().equals(quest)) {
                    config = ConfigManager.getInstance().getTaskConfig(def.getFullId());
                    if (config != null) break;
                }
            }
        }

        return config;
    }

    /** 当前是否处于 habitrain 停电模式（仅限该模式改杀手任务金）。 */
    private static boolean isBlackoutMode(Player player) {
        if (!(player.level() instanceof ServerLevel level)) return false;
        return GameModeRegistry.getActiveForLevel(level)
                .map(gm -> "habitrain:blackout".equals(gm.getId()))
                .orElse(false);
    }

    /**
     * 真正的杀手（非警长/vigilante）。
     * 警长 canUseKiller=true 但仍算好人，应继续走 SRE 平民奖励分支。
     */
    private static boolean isTrueKiller(Player player) {
        SRERole role = getCurrentRole(player);
        if (role == null) return false;
        if (role.isVigilanteTeam()) return false;
        return role.isKiller();
    }

    private static boolean isMimeKiller(Player player) {
        try {
            return HabiRoles.isHabiRole(player, HabiRoles.MIME_KILLER);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 读取 SRE 平民任务金配置。
     * 不直接依赖 {@code SREConfig} 类型（autoconfig ConfigData 不在 compile classpath）。
     */
    private static int readCivilianTaskReward() {
        try {
            Class<?> cfgClass = Class.forName("io.wifi.starrailexpress.SREConfig");
            Object instance = cfgClass.getMethod("instance").invoke(null);
            Object value = cfgClass.getField("civilianTaskReward").get(instance);
            if (value instanceof Number n) {
                return n.intValue();
            }
        } catch (Throwable t) {
            LOGGER.debug("[Reward] 无法读取 SREConfig.civilianTaskReward，使用默认 {}", FALLBACK_CIVILIAN_TASK_REWARD, t);
        }
        return FALLBACK_CIVILIAN_TASK_REWARD;
    }

    /**
     * 对齐 SRE 原版平民完成任务金币：
     * {@code (civilianTaskReward + streakBonus) * parallelMultiplier}
     */
    private static int civilianGoldFormula(int taskStreak, boolean isParallelTask) {
        int streakBonus = Math.min(
                taskStreak * GameConstants.STREAK_BONUS_PER_LEVEL,
                GameConstants.MAX_STREAK_BONUS);
        float rewardMultiplier = isParallelTask
                ? GameConstants.PARALLEL_TASK_REWARD_MULTIPLIER
                : 1f;
        return (int) ((readCivilianTaskReward() + streakBonus) * rewardMultiplier);
    }

    @Inject(
            method = "callOnFinishQuest(Lnet/minecraft/world/entity/player/Player;Ljava/lang/String;IZ)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void habitrain$beforeCallOnFinishQuest(Player player, String quest, int taskStreak,
                                                          boolean isParallelTask, CallbackInfo ci) {
        headHandled.set(false);
        if (player == null || player.level() == null || player.level().isClientSide) return;

        // 默剧杀手：可做任务但不加金币，并累计狂暴折扣
        // 注意：CCA KEY.get() 在组件缺失时抛 NoSuchElementException，不会返回 null
        if (isMimeKiller(player)) {
            var progressionOpt = SREPlayerProgressionComponent.KEY.maybeGet(player);
            if (progressionOpt.isEmpty()) {
                // 无 progression 时仍取消原逻辑发金，并尽量累计折扣
                ci.cancel();
                headHandled.set(true);
                try {
                    MimeKillerComponent.KEY.maybeGet(player).ifPresent(MimeKillerComponent::onTaskComplete);
                } catch (Throwable ignored) {}
                LOGGER.debug("[Reward] 默剧杀手无 player_progression，跳过 onRoundQuestFinished: {}",
                        player.getName().getString());
                return;
            }
            try {
                ci.cancel();
                headHandled.set(true);
                progressionOpt.get().onRoundQuestFinished(quest);
                SRERole role = getCurrentRole(player);
                if (role != null) {
                    role.onFinishQuest(player, quest);
                }
                try {
                    MimeKillerComponent.KEY.maybeGet(player).ifPresent(MimeKillerComponent::onTaskComplete);
                } catch (Throwable ignored) {}
                LOGGER.info("[Reward] 默剧杀手任务无金币，狂暴折扣+50: {}", player.getName().getString());
            } catch (Exception e) {
                LOGGER.error("[Reward] 默剧杀手任务处理失败", e);
            }
            return;
        }

        TaskConfigEntry config = findConfigForQuest(quest);

        // 配置自定义金币优先（所有模式）
        if (config != null && config.hasGoldReward) {
            // 先确认必要组件已附加，再 cancel 原 SRE 逻辑；否则放行原逻辑，
            // 避免 cancel 后既拿不到自定义奖励也拿不到 SRE 基础奖励。
            // CCA KEY.get() 缺组件会抛异常，必须用 maybeGet。
            var progressionOpt = SREPlayerProgressionComponent.KEY.maybeGet(player);
            if (progressionOpt.isEmpty()) {
                return;
            }
            try {
                ci.cancel();
                headHandled.set(true);

                progressionOpt.get().onRoundQuestFinished(quest);
                SRERole role = getCurrentRole(player);

                if (role != null) {
                    int actualReward = isParallelTask
                            ? Math.max(1, config.goldReward / 2)
                            : config.goldReward;

                    SREPlayerShopComponent.KEY.maybeGet(player).ifPresent(shop -> {
                        shop.addToBalance(actualReward);
                        LOGGER.info("[Reward] 自定义金币奖励 (替换SRE基础): {} (并列={}) 给 {}",
                                actualReward, isParallelTask, player.getName().getString());
                    });

                    role.onFinishQuest(player, quest);
                }
            } catch (Exception e) {
                LOGGER.error("[Reward] 发放自定义金币奖励失败", e);
            }
            return;
        }

        // 停电模式：杀手本人完成任务时，金币按好人公式发放（替换 killerTaskIncome 分支）。
        // 警长/vigilante 不进此分支，继续走 SRE 平民奖励。
        if (isBlackoutMode(player) && isTrueKiller(player)) {
            var progressionOpt = SREPlayerProgressionComponent.KEY.maybeGet(player);
            if (progressionOpt.isEmpty()) {
                return;
            }
            try {
                ci.cancel();
                headHandled.set(true);

                progressionOpt.get().onRoundQuestFinished(quest);
                SRERole role = getCurrentRole(player);
                if (role != null) {
                    int actualReward = civilianGoldFormula(taskStreak, isParallelTask);
                    SREPlayerShopComponent.KEY.maybeGet(player).ifPresent(shop -> {
                        shop.addToBalance(actualReward);
                        LOGGER.info("[Reward] 停电杀手任务金对齐好人: {} (streak={}, 并列={}) 给 {}",
                                actualReward, taskStreak, isParallelTask, player.getName().getString());
                    });
                    role.onFinishQuest(player, quest);
                }
            } catch (Exception e) {
                LOGGER.error("[Reward] 停电杀手任务金发放失败", e);
            }
        }
    }

    @Inject(
            method = "callOnFinishQuest(Lnet/minecraft/world/entity/player/Player;Ljava/lang/String;IZ)V",
            at = @At("TAIL"),
            remap = false
    )
    private static void habitrain$afterCallOnFinishQuest(Player player, String quest, int taskStreak,
                                                         boolean isParallelTask, CallbackInfo ci) {
        try {
            if (player == null || player.level() == null || player.level().isClientSide) return;

            try {
                RoleEventDispatcher.INSTANCE.notifyFinishQuest(player, quest, taskStreak, isParallelTask);
            } catch (Throwable t) {
                LOGGER.debug("[Reward] v2 finish-quest hook failed", t);
            }

            TaskConfigEntry config = findConfigForQuest(quest);
            if (config == null) return;

            // 仅当 HEAD 已接管（cancel 了原方法、发了自定义金）时才发自定义情绪，
            // 避免 progression==null 路径下 SRE 原版情绪 + 自定义情绪双发（P2-28）。
            if (headHandled.get() && config.hasEmotionReward) {
                SREPlayerMoodComponent.KEY.maybeGet(player).ifPresent(mood -> {
                    float actualReward = isParallelTask
                            ? config.emotionReward * 1.5f
                            : config.emotionReward;
                    mood.addMood(actualReward);
                    LOGGER.info("[Reward] 发放配置情绪奖励: {} (并列={}) 给 {}",
                            String.format("%.2f", actualReward), isParallelTask, player.getName().getString());
                });
            }
        } catch (Exception e) {
            LOGGER.error("[Reward] 发放自定义情绪奖励失败", e);
        } finally {
            headHandled.remove();
        }
    }
}
