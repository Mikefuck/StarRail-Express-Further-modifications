package com.habitrain.core.game.blackout.shop;

import com.habitrain.core.api.GameModeRegistry;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 监听 {@code decocraft:rotary_phone_red} 方块右键，打开停电任务商店 GUI。
 * <p>
 * 门控同 {@link com.habitrain.core.game.blackout.BlackoutPhoneHandler}：
 * 服务端、主手、停电模式对局中、存活。
 */
public final class BlackoutTaskShopHandler {

    public static Block getRotaryPhoneRedBlock() {
        return com.habitrain.core.game.blackout.BlackoutOverlayTypes.getRotaryPhoneRedBlock();
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            Block block = level.getBlockState(pos).getBlock();
            Block rotary = getRotaryPhoneRedBlock();
            if (rotary == null || rotary == Blocks.AIR) return InteractionResult.PASS;
            if (!block.equals(rotary)) return InteractionResult.PASS;

            if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

            var gameMode = GameModeRegistry.getActiveForLevel(serverLevel);
            if (gameMode.isEmpty() || !"habitrain:blackout".equals(gameMode.get().getId())) {
                return InteractionResult.PASS;
            }

            if (serverPlayer.isSpectator()) {
                return InteractionResult.PASS;
            }
            // 仅存活玩家可开店；禁止 UI 路径 auto-revive
            if (!com.habitrain.core.game.blackout.BlackoutRoleManager.isAlive(serverLevel, serverPlayer.getUUID())) {
                return InteractionResult.PASS;
            }

            // 记录红色电话交互会话，C2S 购买必须通过此门闩
            com.habitrain.core.game.blackout.BlackoutPhoneSessionGate.markOpen(
                    com.habitrain.core.game.blackout.BlackoutPhoneSessionGate.Kind.TASK_SHOP,
                    serverPlayer, pos);

            // 构建目录快照 + 发送 Open payload
            var entries = BlackoutTaskShopService.visibleEntries(serverLevel, serverPlayer);
            int balance = 0;
            try {
                var shop = SREPlayerShopComponent.KEY.get(serverPlayer);
                if (shop != null) balance = shop.balance;
            } catch (Exception ignored) {}
            boolean destroyed = BlackoutTaskShopState.isGeneratorDestroyed(serverLevel);
            boolean restoreUsed = BlackoutTaskShopState.isRestoreUsed(serverLevel);

            var payload = new com.habitrain.core.network.BlackoutTaskShopOpenPayload(
                    balance, destroyed, restoreUsed,
                    buildEntryPayloads(serverLevel, serverPlayer, entries));
            ServerPlayNetworking.send(serverPlayer, payload);

            serverPlayer.serverLevel().playSound(
                    null,
                    serverPlayer.blockPosition(),
                    com.habitrain.core.HabiTrainCore.PHONE_RING_SOUND,
                    net.minecraft.sounds.SoundSource.PLAYERS,
                    1.0f,
                    1.0f
            );

            return InteractionResult.SUCCESS;
        });

        com.habitrain.core.HabiTrainCore.LOGGER.info("[TaskShopHandler] registered for decocraft:rotary_phone_red");
    }

    private static java.util.List<com.habitrain.core.network.BlackoutTaskShopOpenPayload.Entry> buildEntryPayloads(
            ServerLevel level, ServerPlayer player,
            java.util.List<BlackoutTaskShopCatalog.Entry> entries) {
        java.util.List<com.habitrain.core.network.BlackoutTaskShopOpenPayload.Entry> out = new java.util.ArrayList<>();
        for (BlackoutTaskShopCatalog.Entry e : entries) {
            String reason = BlackoutTaskShopService.purchaseBlockReason(level, player, e);
            boolean affordable = reason == null;
            out.add(new com.habitrain.core.network.BlackoutTaskShopOpenPayload.Entry(
                    e.key(), e.displayName(), e.resolvePrice(), affordable,
                    reason == null ? "" : reason));
        }
        return out;
    }

    private BlackoutTaskShopHandler() {}
}
