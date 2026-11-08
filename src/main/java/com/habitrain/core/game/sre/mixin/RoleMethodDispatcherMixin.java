package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.component.MimeKillerComponent;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import com.habitrain.core.role.behavior.RoleEventDispatcher;
import io.wifi.starrailexpress.api.RoleMethodDispatcher;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREPlayerMoodComponent;
import io.wifi.starrailexpress.cca.SREPlayerProgressionComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.progression.ProgressionDataManager;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RoleMethodDispatcher.class, remap = false)
public class RoleMethodDispatcherMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleMethodDispatcherMixin");


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


    private static boolean isMimeKiller(Player player) {
        try {
            return HabiRoles.isHabiRole(player, HabiRoles.MIME_KILLER);
        } catch (Throwable t) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
            return false;
        }
    }

    /**
     * callOnFinishQuest 的公共后置效果：v2 finish-quest 钩子（所有路径都要触发）与
     * 配置情绪奖励（仅 HEAD 接管路径——已 cancel 原方法、SRE 原版情绪不会发放，
     * 避免双发 P2-28）。
     *
     * <p>HEAD 的 cancel 分支必须直接调用本方法：{@code ci.cancel()} 后原方法体被整体
     * 跳过，位于方法体内的 {@code @At("TAIL")} 注入不会执行，不能依赖 TAIL（审查 S1）。
     */
    private static void habitrain$postQuestEffects(Player player, String quest, int taskStreak,
                                                   boolean isParallelTask, boolean headTakeover) {
        try {
            RoleEventDispatcher.INSTANCE.notifyFinishQuest(player, quest, taskStreak, isParallelTask);
        } catch (Throwable t) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
            LOGGER.debug("[Reward] v2 finish-quest hook failed", t);
        }
        try {
            SlothComponent.onAnyTaskFinished(player, quest);
        } catch (Throwable t) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
            LOGGER.debug("[Sloth] finish-quest tracking failed", t);
        }
        if (!headTakeover) {
            return;
        }
        try {
            TaskConfigEntry config = findConfigForQuest(quest);
            if (config == null || !config.hasEmotionReward) {
                return;
            }
            SREPlayerMoodComponent.KEY.maybeGet(player).ifPresent(mood -> {
                float actualReward = isParallelTask
                        ? config.emotionReward * 1.5f
                        : config.emotionReward;
                mood.addMood(actualReward);
                LOGGER.info("[Reward] 发放配置情绪奖励: {} (并列={}) 给 {}",
                        String.format(java.util.Locale.ROOT, "%.2f", actualReward), isParallelTask, player.getName().getString());
            });
        } catch (Exception e) {
            LOGGER.error("[Reward] 发放自定义情绪奖励失败", e);
        }
    }

    @Inject(
            method = "callOnFinishQuest(Lnet/minecraft/world/entity/player/Player;Ljava/lang/String;IZ)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void habitrain$beforeCallOnFinishQuest(Player player, String quest, int taskStreak,
                                                          boolean isParallelTask, CallbackInfo ci) {
        if (player == null || player.level() == null || player.level().isClientSide) return;

        // 默剧杀手：可做任务但不加金币，并累计狂暴折扣
        if (isMimeKiller(player)) {
            try {
                ProgressionDataManager.onRoundQuestFinished(player, quest);
                SRERole role = getCurrentRole(player);
                if (role != null) {
                    role.onFinishQuest(player, quest);
                }
                try {
                    MimeKillerComponent.KEY.maybeGet(player).ifPresent(MimeKillerComponent::onTaskComplete);
                } catch (Exception failure) { LOGGER.warn("Failed to apply Mime task completion discount", failure); }
                ci.cancel();
                LOGGER.info("[Reward] 默剧杀手任务无金币，狂暴折扣+50: {}", player.getName().getString());
            } catch (Exception e) {
                LOGGER.error("[Reward] 默剧杀手任务处理失败", e);
            }
            if (ci.isCancelled()) {
                habitrain$postQuestEffects(player, quest, taskStreak, isParallelTask, true);
            }
            return;
        }

        TaskConfigEntry config = findConfigForQuest(quest);

        // 配置自定义金币优先（所有模式）
        if (config != null && config.hasGoldReward) {
            // 先确认必要组件已附加，再接管原 SRE 逻辑；否则放行原逻辑，
            // 避免 cancel 后既拿不到自定义奖励也拿不到 SRE 基础奖励。
            var progressionOpt = SREPlayerProgressionComponent.KEY.maybeGet(player);
            if (progressionOpt.isEmpty()) {
                return;
            }
            try {
                SRERole role = getCurrentRole(player);
                ProgressionDataManager.onRoundQuestFinished(player, quest);

                if (role != null) {
                    int streakBonus = Math.min(taskStreak * io.wifi.starrailexpress.game.GameConstants.STREAK_BONUS_PER_LEVEL,
                            io.wifi.starrailexpress.game.GameConstants.MAX_STREAK_BONUS);
                    float rewardMultiplier = isParallelTask
                            ? io.wifi.starrailexpress.game.GameConstants.PARALLEL_TASK_REWARD_MULTIPLIER
                            : 1f;
                    if (role.isInnocent() || role.isNeutrals()) {
                        int actualReward = (int) ((config.goldReward + streakBonus) * rewardMultiplier);
                        SREPlayerShopComponent.KEY.maybeGet(player).ifPresent(shop -> {
                            shop.addToBalance(actualReward);
                            LOGGER.info("[Reward] 自定义金币奖励 (替换SRE基础): {} (并列={}, 连击加成={}) 给 {}",
                                    actualReward, isParallelTask, streakBonus, player.getName().getString());
                        });
                    } else if (role.isKiller() && !role.isNeutrals() && !role.isInnocent()) {
                        // 对齐上游：杀手自身做伪装任务不拿基础任务金币，保持上游 killerTaskIncome 分支
                        int killerGain = (int) (io.wifi.starrailexpress.SREConfig.instance().killerTaskIncome * rewardMultiplier);
                        if (killerGain > 0) {
                            player.level().players().forEach(a -> {
                                SRERole aRole = getCurrentRole(a);
                                if (aRole != null && aRole.canUseKiller() && !aRole.isNeutrals() && !aRole.isInnocent()) {
                                    SREPlayerShopComponent.KEY.maybeGet(a).ifPresent(s -> s.addToBalance(killerGain));
                                }
                            });
                        }
                    }

                    role.onFinishQuest(player, quest);
                }
                ci.cancel();
            } catch (Exception e) {
                LOGGER.error("[Reward] 发放自定义金币奖励失败", e);
            }
            if (ci.isCancelled()) {
                habitrain$postQuestEffects(player, quest, taskStreak, isParallelTask, true);
            }
            return;
        }

    }

    @Inject(
            method = "callOnFinishQuest(Lnet/minecraft/world/entity/player/Player;Ljava/lang/String;IZ)V",
            at = @At("TAIL"),
            remap = false
    )
    private static void habitrain$afterCallOnFinishQuest(Player player, String quest, int taskStreak,
                                                         boolean isParallelTask, CallbackInfo ci) {
        // 仅在 HEAD 未 cancel（原 SRE 逻辑正常执行）时运行；HEAD cancel 路径已在其分支内
        // 直接调用 habitrain$postQuestEffects。SRE 原版已发自己的情绪，此处不再发自定义
        // 情绪，避免双发（P2-28）。
        if (player == null || player.level() == null || player.level().isClientSide) return;
        habitrain$postQuestEffects(player, quest, taskStreak, isParallelTask, false);
    }
}
