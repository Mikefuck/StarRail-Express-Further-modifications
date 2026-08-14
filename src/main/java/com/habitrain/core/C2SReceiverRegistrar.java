package com.habitrain.core;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MenuGateService;
import com.habitrain.core.game.blackout.BlackoutExileVoteManager;
import com.habitrain.core.network.BlackoutPhoneOpenPayload;
import com.habitrain.core.game.blackout.BlackoutPoliceHireService;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.network.*;
import com.habitrain.core.util.SubtitleNotifier;
import com.habitrain.core.vote.OptionVoteManager;
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
                // Mod 菜单门控：专用服务器上门控开启且未授权时，服务端权威拒绝配置写入
                if (context.server().isDedicatedServer() && MenuGateService.isEnabled()
                        && !MenuGateService.isAllowed(player)) {
                    player.sendSystemMessage(Component.literal("§c当前为未授权的访问：未获得服务器授权修改配置"));
                    return;
                }
                // merge 语义：仅覆盖 OP 客户端发送的条目；失败则不 save/不广播
                boolean merged = ConfigManager.getInstance().mergeFromJsonString(payload.getConfigJson());
                if (!merged) {
                    player.sendSystemMessage(Component.literal(
                            "§c配置合并失败：JSON 无效或字段类型错误，服务端配置未改动"));
                    LOGGER.warn("玩家 {} 的配置 merge 被拒绝（解析失败）", player.getName().getString());
                    return;
                }
                ConfigManager.getInstance().save();
                ConfigManager.getInstance().applyMinigameEnforcement(context.server());
                // Rebuild role override engine with updated config
                com.habitrain.core.role.override.RoleOverrideEngine.getInstance().rebuild();
                com.habitrain.core.role.override.RoleOverrideLifecycleHandler.publishSnapshotAfterRebuild();
                com.habitrain.core.game.sre.roleoverride.SreRoleOverrideRefreshService
                        .refreshServer(context.server());
                LOGGER.info("玩家 {} 通过 ModMenu 更新了服务端配置", player.getName().getString());
                if (context.server().isSingleplayer()) return;
                // FullConfigSyncPayload 已含 global + tasks + gameModes + minigames + shader，
                // 单独的 TaskConfigPayload / ShaderConfigPayload 广播冗余，去掉（P1-16）。
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
                // 关光影（空包名）始终允许；白名单只拦截“启用了但不在名单内”的光影
                if (shaderPackName == null || shaderPackName.isEmpty()) return;
                boolean allowed = cfg.getShaderWhitelist().stream()
                        .anyMatch(name -> name.equalsIgnoreCase(shaderPackName));
                if (!allowed) {
                    player.connection.disconnect(Component.literal(
                            "§c✖ 未授权的光影包\n\n" +
                            "§7你使用的光影包 §e" + shaderPackName + " §7不在服务器白名单中。\n" +
                            "§7请更换为允许的光影包，或关闭光影后重新加入。\n\n" +
                            "§7如需帮助，请联系服务器管理员。"));
                }
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
        // C2S 通用选项投票接收器（模式/地图等）；voteId 不匹配时 manager 内 no-op
        ServerPlayNetworking.registerGlobalReceiver(OptionVoteCastPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer voter = context.player();
                if (voter == null) return;
                ServerLevel level = voter.serverLevel();
                if (level == null) return;
                OptionVoteManager.cast(level, voter.getUUID(), payload.voteId(), payload.optionId());
            });
        });
        // C2S 贪婪匿名交易确认/取消（与 /habi_api greed_trade 等价）
        ServerPlayNetworking.registerGlobalReceiver(GreedTradeActionPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) return;
                String action = payload.action() == null ? "" : payload.action().trim().toLowerCase();
                String sid = payload.sessionId() == null ? "" : payload.sessionId();
                if ("confirm".equals(action)) {
                    com.habitrain.core.game.sre.role.sins.trade.GreedTradeManager.confirm(player, sid);
                } else if ("cancel".equals(action)) {
                    com.habitrain.core.game.sre.role.sins.trade.GreedTradeManager.cancel(player, sid);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(GreedTradeSelectPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player != null) {
                        com.habitrain.core.game.sre.role.sins.trade.GreedTradeManager
                                .openSelectedTrade(player, payload.partnerId());
                    }
                }));
        ServerPlayNetworking.registerGlobalReceiver(RoleActionC2SPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || payload == null || payload.actionId() == null) {
                        return;
                    }
                    // The service runs the §12.4 validation order and echoes the
                    // result (with the request sequence) through its bound ResultSender.
                    com.habitrain.core.api.role.v2.action.RoleActionApi.instance()
                            .receiveC2S(player, payload.actionId(), payload.payload(), payload.sequence());
                }));
        // C2S 角色扩展 v2 配置更新（§13.1）：仅 OP4 且通过菜单门控的服务端权威应用。
        ServerPlayNetworking.registerGlobalReceiver(RoleConfigUpdatePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null || payload == null) {
                    return;
                }
                if (!player.hasPermissions(4)) {
                    player.sendSystemMessage(Component.literal("§c你没有权限修改角色扩展配置（需要 OP 权限）"));
                    return;
                }
                if (context.server().isDedicatedServer() && MenuGateService.isEnabled()
                        && !MenuGateService.isAllowed(player)) {
                    player.sendSystemMessage(Component.literal("§c当前为未授权的访问：未获得服务器授权修改配置"));
                    return;
                }
                if (!com.habitrain.core.role.config.RoleExtensionConfigService.INSTANCE
                        .applyFromJson(payload.configJson())) {
                    player.sendSystemMessage(Component.literal(
                            "§c角色扩展配置合并失败：JSON 无效或字段类型错误，配置未改动"));
                    LOGGER.warn("玩家 {} 的角色扩展配置更新被拒绝（解析失败）",
                            player.getName().getString());
                    return;
                }
                com.habitrain.core.role.config.RoleExtensionConfigService.INSTANCE.save();
                com.habitrain.core.role.config.RoleConfigApplyService.applyAndBroadcast(context.server());
                player.sendSystemMessage(Component.literal(
                        "§a角色扩展配置已应用；若在对局中，将下一局生效。"));
                LOGGER.info("玩家 {} 更新了角色扩展 v2 配置", player.getName().getString());
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
