package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.util.SubtitleNotifier;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监听 trainmurdermystery:horn 方块右键，实现二次拉动放逐投票。
 *
 * 第一次拉动（免费）：显示 MC 原生标题 "再次拉动发动投票"，10 秒有效。
 * 第二次拉动（扣 500 金）：发起放逐投票。
 */
public final class BlackoutHornVoteHandler {
    private static final ResourceLocation HORN_ID = ResourceLocation.parse("trainmurdermystery:horn");
    private static final int CONFIRM_WINDOW_SECONDS = 10;
    private static final int EXILE_COST = 500;

    // player UUID -> tick when confirmation expires
    private static final Map<UUID, Long> confirmWindows = new ConcurrentHashMap<>();

    private static Block cachedHorn = null;

    private static Block getHornBlock() {
        if (cachedHorn == null || cachedHorn == Blocks.AIR) {
            cachedHorn = BuiltInRegistries.BLOCK.get(HORN_ID);
        }
        return cachedHorn;
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide) return InteractionResult.PASS;
            if (!hand.equals(net.minecraft.world.InteractionHand.MAIN_HAND)) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            Block block = level.getBlockState(pos).getBlock();

            Block hornBlock = getHornBlock();
            if (hornBlock == null || hornBlock == Blocks.AIR) return InteractionResult.PASS;
            if (!block.equals(hornBlock)) return InteractionResult.PASS;

            if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

            // 停电模式对局检查
            var gameMode = GameModeRegistry.getActiveForLevel(serverLevel);
            if (gameMode.isEmpty() || !"habitrains:blackout".equals(gameMode.get().getId())) {
                return InteractionResult.PASS;
            }

            // 发起者必须存活
            if (!BlackoutRoleManager.isAlive(serverLevel, serverPlayer.getUUID())) {
                return InteractionResult.PASS;
            }

            UUID playerId = serverPlayer.getUUID();
            long now = serverLevel.getGameTime();
            Long expiry = confirmWindows.get(playerId);

            if (expiry != null && now < expiry) {
                // 第二次拉动
                confirmWindows.remove(playerId);

                // 不能有正在进行的放逐投票
                if (BlackoutExileVoteManager.isVoteActive(serverLevel)) {
                    SubtitleNotifier.sendTop(serverPlayer, Component.empty(),
                            Component.literal("§c当前已有投票正在进行"), 60);
                    return InteractionResult.SUCCESS;
                }

                // 扣金币校验
                var shop = SREPlayerShopComponent.KEY.get(serverPlayer);
                if (shop == null || shop.balance < EXILE_COST) {
                    SubtitleNotifier.sendTop(serverPlayer, Component.empty(),
                            Component.literal("§c发动投票需要 " + EXILE_COST + " 金币"), 60);
                    return InteractionResult.SUCCESS;
                }

                shop.addToBalance(-EXILE_COST);
                BlackoutExileVoteManager.startVote(serverLevel, serverPlayer);

                HabiTrainCore.LOGGER.info("[HornVote] {} initiated exile vote (cost: {})",
                        serverPlayer.getName().getString(), EXILE_COST);
            } else {
                // 第一次拉动
                confirmWindows.put(playerId, now + CONFIRM_WINDOW_SECONDS * 20L);
                serverPlayer.sendSystemMessage(Component.literal("§e再次拉动发动投票"), true);
            }

            return InteractionResult.SUCCESS;
        });

        HabiTrainCore.LOGGER.info("[HornVoteHandler] registered for trainmurdermystery:horn");
    }

    /** 玩家淘汰/死亡时清除确认窗口 */
    public static void onPlayerRemoved(UUID playerId) {
        confirmWindows.remove(playerId);
    }

    private BlackoutHornVoteHandler() {}
}
