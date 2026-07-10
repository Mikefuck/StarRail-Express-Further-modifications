package com.habitrain.core;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.blackout.BlackoutExileVoteManager;
import com.habitrain.core.network.BlackoutPhoneOpenPayload;
import com.habitrain.core.game.blackout.BlackoutPoliceHireService;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutSheriffVoteManager;
import com.habitrain.core.network.*;
import com.habitrain.core.util.SubtitleNotifier;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * C2S 网络包接收器注册器 — 负责注册所有客户端→服务端的数据包接收与路由逻辑。
 * <p>在 {@link HabiTrainCore#onInitialize()} 中调用 {@link #init()}。</p>
 */
public final class C2SReceiverRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|C2SReceiverRegistrar");

    private C2SReceiverRegistrar() {}

    public static void init() {
        // C2S 配置更新接收器
        ServerPlayNetworking.registerGlobalReceiver(ConfigUpdatePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) return;
                if (!player.hasPermissions(4)) {
                    player.sendSystemMessage(Component.literal("§c你没有权限修改服务端配置（需要 OP 权限）"));
                    return;
                }
                ConfigManager.getInstance().loadFromJsonString(payload.getConfigJson());
                ConfigManager.getInstance().save();
                ConfigManager.getInstance().applyMinigameEnforcement(context.server());
                LOGGER.info("玩家 {} 通过 ModMenu 更新了服务端配置", player.getName().getString());
                if (context.server().isSingleplayer()) return;
                TaskConfigPayload.broadcastToAll(context.server());
                ShaderConfigPayload.broadcastToAll(context.server());
                // 广播完整配置，让所有客户端的全局项同步到服务端最新值。
                FullConfigSyncPayload.broadcastToAll(context.server());
            });
        });
        // C2S 光影包信息接收器
        ServerPlayNetworking.registerGlobalReceiver(ShaderInfoPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) return;
                ConfigManager cfg = ConfigManager.getInstance();
                if (!cfg.isShaderWhitelistEnabled()) return;
                String shaderPackName = payload.getShaderPackName();
                if (shaderPackName.isEmpty()) return;
                boolean allowed = cfg.getShaderWhitelist().stream()
                        .anyMatch(name -> name.equalsIgnoreCase(shaderPackName));
                if (!allowed) {
                    player.connection.disconnect(Component.literal(
                            "§c✖ 未授权的光影包\n\n" +
                            "§7你使用的光影包 §e" + shaderPackName + " §7不在服务器白名单中。\n" +
                            "§7请更换为允许的光影包后重新加入。\n\n" +
                            "§7如需帮助，请联系服务器管理员。"));
                }
            });
        });
        // C2S 警长投票接收器：客户端投票 → 服务端记票
        ServerPlayNetworking.registerGlobalReceiver(BlackoutSheriffVoteCastPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer voter = context.player();
                if (voter == null) return;
                ServerLevel level = voter.serverLevel();
                if (level == null) return;
                BlackoutSheriffVoteManager.castVote(level, voter.getUUID(), payload.targetPlayerId(), payload.slotIndex());
            });
        });
        // C2S 电话聘请警察接收器
        ServerPlayNetworking.registerGlobalReceiver(BlackoutHirePolicePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) return;
                ServerLevel level = player.serverLevel();
                if (level == null) return;

                Component error = BlackoutPoliceHireService.tryHire(level, player);

                // 发送聘请结果回执，客户端据此更新 statusText
                if (error != null) {
                    ServerPlayNetworking.send(player, new BlackoutHireResultPayload(false, error.getString()));
                    SubtitleNotifier.sendTop(
                            player, Component.empty(), error, 60);
                } else {
                    ServerPlayNetworking.send(player, new BlackoutHireResultPayload(true, ""));
                    SubtitleNotifier.sendTop(
                            player, Component.empty(), Component.literal("§a已成功聘请警察！"), 60);
                }

                // 同时刷新电话状态（更新余额、已聘请状态等）
                boolean unlocked = BlackoutPoliceHireService.isPhoneUnlocked(level);
                int remainingLock = BlackoutPoliceHireService.getRemainingLockSeconds(level);
                int balance = 0;
                try {
                    var shop = io.wifi.starrailexpress.cca.SREPlayerShopComponent.KEY.get(player);
                    if (shop != null) balance = shop.balance;
                } catch (Exception e) {
                    LOGGER.warn("读取玩家 {} 的商店余额时失败", player.getName().getString(), e);
                }
                boolean hasHired = BlackoutPoliceHireService.hasHired(level, player.getUUID());
                int sheriffCount = BlackoutRoleManager.getSheriffCount(level);
                int killerCount = BlackoutRoleManager.getRemainingBad(level);
                ServerPlayNetworking.send(player, new BlackoutPhoneOpenPayload(
                        unlocked, remainingLock, balance, hasHired, sheriffCount, killerCount));
            });
        });
        // C2S 通用投票接收器（用于放逐投票等）
        ServerPlayNetworking.registerGlobalReceiver(BlackoutVoteCastPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer voter = context.player();
                if (voter == null) return;
                ServerLevel level = voter.serverLevel();
                if (level == null) return;
                if (VotePurpose.EXILE.equals(payload.purpose())) {
                    BlackoutExileVoteManager.castVote(level, voter.getUUID(), payload.targetPlayerId());
                }
            });
        });
        // C2S 停电任务商店购买接收器
        ServerPlayNetworking.registerGlobalReceiver(BlackoutTaskShopBuyPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) return;
                ServerLevel level = player.serverLevel();
                if (level == null) return;

                com.habitrain.core.game.blackout.shop.BlackoutTaskShopCatalog.Entry entry =
                        com.habitrain.core.game.blackout.shop.BlackoutTaskShopCatalog.findByKey(payload.entryKey());
                if (entry == null) {
                    ServerPlayNetworking.send(player, new BlackoutTaskShopResultPayload(false, "无效条目"));
                    return;
                }
                String error = com.habitrain.core.game.blackout.shop.BlackoutTaskShopService.tryPurchase(level, player, entry);
                if (error != null) {
                    ServerPlayNetworking.send(player, new BlackoutTaskShopResultPayload(false, error));
                    SubtitleNotifier.sendTop(player, Component.empty(), Component.literal("§c" + error), 60);
                } else {
                    ServerPlayNetworking.send(player, new BlackoutTaskShopResultPayload(true, ""));
                }
                // 刷新商店 Open 状态（余额/可买性变化）
                refreshShopOpen(level, player);
            });
        });
    }

    /** 购买后重发商店 Open payload 刷新客户端。 */
    private static void refreshShopOpen(ServerLevel level, ServerPlayer player) {
        var entries = com.habitrain.core.game.blackout.shop.BlackoutTaskShopService.visibleEntries(level, player);
        int balance = 0;
        try {
            var shop = io.wifi.starrailexpress.cca.SREPlayerShopComponent.KEY.get(player);
            if (shop != null) balance = shop.balance;
        } catch (Exception ignored) {}
        boolean destroyed = com.habitrain.core.game.blackout.shop.BlackoutTaskShopState.isGeneratorDestroyed(level);
        boolean restoreUsed = com.habitrain.core.game.blackout.shop.BlackoutTaskShopState.isRestoreUsed(level);
        var out = new java.util.ArrayList<BlackoutTaskShopOpenPayload.Entry>();
        for (var e : entries) {
            String reason = com.habitrain.core.game.blackout.shop.BlackoutTaskShopService.purchaseBlockReason(level, player, e);
            out.add(new BlackoutTaskShopOpenPayload.Entry(e.key(), e.displayName(), e.resolvePrice(),
                    reason == null, reason == null ? "" : reason));
        }
        ServerPlayNetworking.send(player, new BlackoutTaskShopOpenPayload(balance, destroyed, restoreUsed, out));
    }
}
