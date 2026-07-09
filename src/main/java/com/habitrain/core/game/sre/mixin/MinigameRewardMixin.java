package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MinigameConfigEntry;
import io.wifi.starrailexpress.cca.SREPlayerMoodComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "io.wifi.starrailexpress.cca.SREPlayerMinigameTaskComponent", remap = false)
public class MinigameRewardMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("MinigameReward");

    @Inject(
            method = "onMinigameBlockCompleted",
            at = @At("RETURN"),
            remap = false
    )
    private void habitrain$applyHabiRewards(ServerPlayer player,
                                            net.minecraft.core.BlockPos pos,
                                            int reward, String minigameId,
                                            CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!cir.getReturnValue()) return;
            MinigameConfigEntry entry = ConfigManager.getInstance().getMinigameConfig(minigameId);
            if (entry == null) return;

            if (entry.hasGoldReward) {
                var shop = SREPlayerShopComponent.KEY.get(player);
                if (shop != null) {
                    shop.addToBalance(entry.goldReward);
                    LOGGER.info("[MinigameReward] 自定义金币奖励: {} 给 {} (小游戏={})",
                            entry.goldReward, player.getName().getString(), minigameId);
                }
            }
            if (entry.hasEmotionReward) {
                var mood = SREPlayerMoodComponent.KEY.get(player);
                if (mood != null) {
                    mood.addMood(entry.emotionReward);
                    LOGGER.info("[MinigameReward] 自定义情绪奖励: {} 给 {} (小游戏={})",
                            String.format("%.2f", entry.emotionReward),
                            player.getName().getString(), minigameId);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[MinigameReward] 发放自定义奖励失败", t);
        }
    }
}