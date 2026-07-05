package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import io.wifi.starrailexpress.api.RoleMethodDispatcher;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREPlayerMoodComponent;
import io.wifi.starrailexpress.cca.SREPlayerProgressionComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
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

    @Inject(
            method = "callOnFinishQuest(Lnet/minecraft/world/entity/player/Player;Ljava/lang/String;IZ)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void habitrain$beforeCallOnFinishQuest(Player player, String quest, int taskStreak,
                                                          boolean isParallelTask, CallbackInfo ci) {
        if (player == null || player.level() == null || player.level().isClientSide) return;

        TaskConfigEntry config = findConfigForQuest(quest);
        if (config == null) return;

        if (config.goldReward >= 0) {
            // 先确认必要组件已附加，再 cancel 原 SRE 逻辑；否则放行原逻辑，
            // 避免 cancel 后 NPE 导致玩家既拿不到自定义奖励也拿不到 SRE 基础奖励。
            SREPlayerProgressionComponent progression = SREPlayerProgressionComponent.KEY.get(player);
            if (progression == null) {
                return;
            }
            try {
                ci.cancel();

                progression.onRoundQuestFinished(quest);
                SRERole role = getCurrentRole(player);

                if (role != null) {
                    int actualReward = isParallelTask
                            ? Math.max(1, config.goldReward / 2)
                            : config.goldReward;

                    var shop = SREPlayerShopComponent.KEY.get(player);
                    if (shop != null) {
                        shop.addToBalance(actualReward);
                        LOGGER.info("[Reward] 自定义金币奖励 (替换SRE基础): {} (并列={}) 给 {}",
                                actualReward, isParallelTask, player.getName().getString());
                    }

                    role.onFinishQuest(player, quest);
                }
            } catch (Exception e) {
                LOGGER.error("[Reward] 发放自定义金币奖励失败", e);
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
        if (player == null || player.level() == null || player.level().isClientSide) return;

        TaskConfigEntry config = findConfigForQuest(quest);
        if (config == null) return;

        try {
            if (config.emotionReward >= 0f) {
                var mood = SREPlayerMoodComponent.KEY.get(player);
                if (mood != null) {
                    float actualReward = isParallelTask
                            ? config.emotionReward * 1.5f
                            : config.emotionReward;
                    mood.addMood(actualReward);
                    LOGGER.info("[Reward] 发放配置情绪奖励: {} (并列={}) 给 {}",
                            String.format("%.2f", actualReward), isParallelTask, player.getName().getString());
                }
            }
        } catch (Exception e) {
            LOGGER.error("[Reward] 发放自定义情绪奖励失败", e);
        }
    }
}
