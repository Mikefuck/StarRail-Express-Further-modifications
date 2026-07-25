package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.util.SubtitleNotifier;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监听 trainmurdermystery:horn 方块右键，实现二次拉动放逐投票。
 *
 * 存活玩家 &lt; 6 时不可发起。
 * 第一次拉动（免费）：显示 MC 原生标题 "再次拉动发动投票"，10 秒有效。
 * 第二次拉动（扣 75 金）：发起放逐投票。
 */
public final class BlackoutHornVoteHandler {
    private static final int CONFIRM_WINDOW_SECONDS = 10;
    private static final int EXILE_COST = 75;
    /** 至少存活人数才能拉汽笛发起放逐投票 */
    private static final int MIN_ALIVE_FOR_EXILE = 6;

    /** (dimension, player) → tick when confirmation expires — 防跨维串窗 */
    private static final Map<ConfirmKey, Long> confirmWindows = new ConcurrentHashMap<>();

    private record ConfirmKey(ResourceKey<Level> dimension, UUID playerId) {}

    private static Block getHornBlock() {
        return BlackoutOverlayTypes.getHornBlock();
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
            if (gameMode.isEmpty() || !"habitrain:blackout".equals(gameMode.get().getId())) {
                return InteractionResult.PASS;
            }

            // 发起者必须存活
            if (!BlackoutRoleManager.isAlive(serverLevel, serverPlayer.getUUID())) {
                return InteractionResult.PASS;
            }

            // 存活人数不足时不可拉汽笛放逐
            int aliveCount = BlackoutRoleManager.getAllAlive(serverLevel).size();
            if (aliveCount < MIN_ALIVE_FOR_EXILE) {
                SubtitleNotifier.sendTop(serverPlayer, Component.empty(),
                        Component.literal("§c存活玩家不足 " + MIN_ALIVE_FOR_EXILE
                                + " 人，无法发起放逐投票（当前 " + aliveCount + "）"), 60);
                return InteractionResult.SUCCESS;
            }

            UUID playerId = serverPlayer.getUUID();
            ConfirmKey key = new ConfirmKey(serverLevel.dimension(), playerId);
            long now = serverLevel.getGameTime();
            Long expiry = confirmWindows.get(key);

            if (expiry != null && now < expiry) {
                // 第二次拉动
                confirmWindows.remove(key);

                // 二次确认时再校验人数（期间可能有人死亡）
                int aliveNow = BlackoutRoleManager.getAllAlive(serverLevel).size();
                if (aliveNow < MIN_ALIVE_FOR_EXILE) {
                    SubtitleNotifier.sendTop(serverPlayer, Component.empty(),
                            Component.literal("§c存活玩家不足 " + MIN_ALIVE_FOR_EXILE
                                    + " 人，无法发起放逐投票（当前 " + aliveNow + "）"), 60);
                    return InteractionResult.SUCCESS;
                }

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
                boolean voteStarted = BlackoutExileVoteManager.startVote(serverLevel, serverPlayer);
                if (!voteStarted) {
                    // 投票未能发起，退款
                    shop.addToBalance(EXILE_COST);
                    SubtitleNotifier.sendTop(serverPlayer, Component.empty(),
                            Component.literal("§c放逐投票发起失败，已退还金币"), 60);
                    return InteractionResult.SUCCESS;
                }

                HabiTrainCore.LOGGER.info("[HornVote] {} initiated exile vote (cost: {})",
                        serverPlayer.getName().getString(), EXILE_COST);
                // 第二次成功发起：放行原版 horn 拉动效果（音效/动画）
                return InteractionResult.PASS;
            } else {
                // 第一次拉动：使用 MC 原生 Title 提示
                confirmWindows.put(key, now + CONFIRM_WINDOW_SECONDS * 20L);
                serverPlayer.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                        Component.literal("§e再次拉动发动投票")));
                serverPlayer.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                        Component.literal("§7再次拉动花费§e" + EXILE_COST + "§7发起放逐投票")));
                serverPlayer.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 60, 10));
                // 返回 SUCCESS 取消原版 horn use → 不播放原版汽笛音效；改以 MC 原生标题作为拉杆反馈
                // （Mike 2026-07-09：第一次拉杆不要原版汽笛音效，但要有拉杆反馈；第二次拉杆才返回确认发起投票）
                return InteractionResult.SUCCESS;
            }
        });

        HabiTrainCore.LOGGER.info("[HornVoteHandler] registered for trainmurdermystery:horn");
    }

    /** 玩家淘汰/死亡时清除确认窗口（所有维度）。 */
    public static void onPlayerRemoved(UUID playerId) {
        if (playerId == null) return;
        confirmWindows.keySet().removeIf(k -> Objects.equals(k.playerId(), playerId));
    }

    /** 对局开始/清理时清空所有确认窗口，避免跨局二次拉动直接扣费 */
    public static void clearAll() {
        confirmWindows.clear();
    }

    private BlackoutHornVoteHandler() {}
}
