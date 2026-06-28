package com.habitrain.taskapi.impl.mixin;

import com.habitrain.taskapi.HabiTrainTaskAPI;
import com.habitrain.taskapi.api.HabiTaskDefinition;
import com.habitrain.taskapi.api.HabiTaskRegistry;
import com.habitrain.taskapi.impl.config.HabiConfigManager;
import com.habitrain.taskapi.impl.config.HabiTaskConfigEntry;
import io.wifi.starrailexpress.api.RoleMethodDispatcher;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREPlayerMoodComponent;
import io.wifi.starrailexpress.cca.SREPlayerProgressionComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin - 注入 {@link RoleMethodDispatcher#callOnFinishQuest} 方法
 * 用于在任务完成时应用ModMenu配置的自定义奖励（金币、情绪值）
 *
 * ★ 关键设计 ── 自定义金币替换机制 ★
 *
 * 当任务的 goldReward 被设置（≥0）时，它会完全替换SRE原版的基础奖励，
 * 而不是在原版奖励之上叠加发放。
 *
 * 例如：用户将某任务的 goldReward 设为 100，
 * 玩家完成任务后最终获得 100 金币（而不是 50+SRE基础 + 100自定义 = 150+）。
 *
 * 实现方式：
 * - @Inject HEAD (cancellable=true):
 *   如果 goldReward ≥ 0，取消原方法体执行（阻止SRE基础奖励发放），
 *   仅发放自定义金币奖励 + 调用必要的非奖励逻辑（progression、role callback）。
 * - @Inject TAIL:
 *   对未设置自定义金币的任务（goldReward = -1），此mixin无影响；
 *   对所有任务统一处理情绪奖励（独立于金币系统）。
 *
 * 版本: v2 (修复金币叠加发放bug)
 */
@Mixin(RoleMethodDispatcher.class)
public class RoleMethodDispatcherMixin {

    /**
     * Shadow - 获取玩家当前角色（原方法为 private static）
     */
    @Shadow(remap = false)
    private static SRERole getCurrentRole(Player player) {
        throw new AssertionError("Shadowed");
    }

    // ========================================================================
    //  配置查找辅助方法
    // ========================================================================

    /**
     * 根据任务名查找对应的任务配置
     * 匹配策略：精确完整ID → 模糊匹配任务名/显示名
     */
    private static HabiTaskConfigEntry findConfigForQuest(String quest) {
        if (quest == null) return null;

        // 1. 尝试精确匹配完整ID格式
        String fullId = quest.contains(":") ? quest : "habitrain_taskapi:" + quest;
        HabiTaskConfigEntry config = HabiConfigManager.getInstance().getTaskConfig(fullId);

        // 2. 如果没有精确匹配，遍历所有任务尝试匹配任务名或显示名
        if (config == null) {
            for (HabiTaskDefinition def : HabiTaskRegistry.getAll()) {
                if (def.getTaskId().equalsIgnoreCase(quest) || def.getDisplayName().equals(quest)) {
                    config = HabiConfigManager.getInstance().getTaskConfig(def.getFullId());
                    if (config != null) break;
                }
            }
        }

        return config;
    }

    // ========================================================================
    //  金币奖励 ── HEAD 注入（可取消）
    //  当 goldReward ≥ 0 时，替换 SRE 原版基础奖励
    // ========================================================================

    @Inject(
            method = "callOnFinishQuest(Lnet/minecraft/world/entity/player/Player;Ljava/lang/String;IZ)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void habitrain$beforeCallOnFinishQuest(Player player, String quest, int taskStreak,
                                                          boolean isParallelTask, CallbackInfo ci) {
        // 防御性检查
        if (player == null || player.level() == null || player.level().isClientSide) return;

        // 查找任务配置
        HabiTaskConfigEntry config = findConfigForQuest(quest);
        if (config == null) return;

        // ====== 金币奖励处理 ======
        if (config.goldReward >= 0) {
            try {
                // ★ 取消原方法执行，阻止SRE发放基础金币奖励
                //    （SRE原版会发放 50+连击 金币给平民/中立，或 5 金币给杀手）
                ci.cancel();

                // ---- 1. 非奖励逻辑：仍然需要执行的 ----
                // 调用任务完成进度追踪（原方法的第一步）
                SREPlayerProgressionComponent.KEY.get(player).onRoundQuestFinished(quest);

                // ---- 2. 获取角色 ----
                SRERole role = getCurrentRole(player);

                if (role != null) {
                    // ---- 3. 发放自定义金币奖励（完全替换SRE基础奖励）----
                    int actualReward = isParallelTask
                            ? Math.max(1, config.goldReward / 2)   // 并列任务：金币减半（最小1）
                            : config.goldReward;

                    var shop = SREPlayerShopComponent.KEY.get(player);
                    if (shop != null) {
                        shop.addToBalance(actualReward);
                        HabiTrainTaskAPI.LOGGER.info("[Reward] 自定义金币奖励 (替换SRE基础): {} (并列={}) 给 {}",
                                actualReward, isParallelTask, player.getName().getString());
                    }

                    // ---- 4. 角色完成任务回调（原方法的最后一步）----
                    role.onFinishQuest(player, quest);
                }
            } catch (Exception e) {
                HabiTrainTaskAPI.LOGGER.error("[Reward] 发放自定义金币奖励失败", e);
            }
            return; // goldReward 已处理，不再执行 TAIL 逻辑中的金币部分
        }

        // goldReward < 0（未设置自定义金币）：不取消原方法，SRE 按默认逻辑发放奖励
        // TAIL 注入仍会处理情绪奖励
    }

    // ========================================================================
    //  情绪奖励 ── TAIL 注入（独立于金币系统，始终执行）
    //  注意：此方法仅处理情绪奖励，金币奖励已在 HEAD 中处理
    // ========================================================================

    @Inject(
            method = "callOnFinishQuest(Lnet/minecraft/world/entity/player/Player;Ljava/lang/String;IZ)V",
            at = @At("TAIL"),
            remap = false
    )
    private static void habitrain$afterCallOnFinishQuest(Player player, String quest, int taskStreak,
                                                         boolean isParallelTask, CallbackInfo ci) {
        // 防御性检查
        if (player == null || player.level() == null || player.level().isClientSide) return;

        // 查找任务配置（可能已经在 HEAD 中被缓存，但为了简洁重新查找一次）
        HabiTaskConfigEntry config = findConfigForQuest(quest);
        if (config == null) return;

        try {
            // ====== 情绪奖励（始终执行，不受金币影响）======
            if (config.emotionReward >= 0f) {
                var mood = SREPlayerMoodComponent.KEY.get(player);
                if (mood != null) {
                    float actualReward = isParallelTask
                            ? config.emotionReward * 1.5f  // 并列任务情绪奖励增加
                            : config.emotionReward;
                    mood.addMood(actualReward);
                    HabiTrainTaskAPI.LOGGER.info("[Reward] 发放配置情绪奖励: {} (并列={}) 给 {}",
                            String.format("%.2f", actualReward), isParallelTask, player.getName().getString());
                }
            }
        } catch (Exception e) {
            HabiTrainTaskAPI.LOGGER.error("[Reward] 发放自定义情绪奖励失败", e);
        }
    }
}
