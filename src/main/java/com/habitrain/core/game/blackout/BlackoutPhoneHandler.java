package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.network.BlackoutPhoneOpenPayload;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 监听 yuushya:street_phone 方块右键，打开电话聘请 GUI。
 */
public final class BlackoutPhoneHandler {
    private static Block getStreetPhoneBlock() {
        return BlackoutOverlayTypes.getStreetPhoneBlock();
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide) return InteractionResult.PASS;
            if (!hand.equals(net.minecraft.world.InteractionHand.MAIN_HAND)) return InteractionResult.PASS;

            // 必须是玩家
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            Block block = level.getBlockState(pos).getBlock();

            Block phoneBlock = getStreetPhoneBlock();
            if (phoneBlock == null || phoneBlock == Blocks.AIR) return InteractionResult.PASS;
            if (!block.equals(phoneBlock)) return InteractionResult.PASS;

            if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

            // 必须是停电模式对局中
            var gameMode = GameModeRegistry.getActiveForLevel(serverLevel);
            if (gameMode.isEmpty() || !"habitrain:blackout".equals(gameMode.get().getId())) {
                return InteractionResult.PASS;
            }

            // 发起者必须存活（旁观者/已淘汰不可开电话 GUI；禁止 UI 路径 auto-revive）
            if (serverPlayer.isSpectator()) {
                return InteractionResult.PASS;
            }
            if (!BlackoutRoleManager.isAlive(serverLevel, serverPlayer.getUUID())) {
                return InteractionResult.PASS;
            }

            // 构造状态包
            boolean unlocked = BlackoutPoliceHireService.isPhoneUnlocked(serverLevel);
            int remainingLock = BlackoutPoliceHireService.getRemainingLockSeconds(serverLevel);
            var shop = SREPlayerShopComponent.KEY.get(serverPlayer);
            int balance = shop != null ? shop.balance : 0;
            boolean hasHired = BlackoutPoliceHireService.hasHired(serverLevel, serverPlayer.getUUID());
            int sheriffCount = BlackoutRoleManager.getSheriffCount(serverLevel);
            int killerCount = BlackoutRoleManager.getRemainingBad(serverLevel);

            ServerPlayNetworking.send(serverPlayer, new BlackoutPhoneOpenPayload(
                    unlocked, remainingLock, balance, hasHired, sheriffCount, killerCount));

            serverPlayer.serverLevel().playSound(
                    null,
                    serverPlayer.blockPosition(),
                    HabiTrainCore.PHONE_OPERATOR_SOUND,
                    net.minecraft.sounds.SoundSource.PLAYERS,
                    1.0f,
                    1.0f
            );

            return InteractionResult.SUCCESS;
        });

        HabiTrainCore.LOGGER.info("[PhoneHandler] registered for yuushya:street_phone");
    }

    private BlackoutPhoneHandler() {}
}
