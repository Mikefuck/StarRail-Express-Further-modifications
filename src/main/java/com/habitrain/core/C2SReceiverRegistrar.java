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
                ConfigUpdateScope scope = ConfigUpdateScope.fromConfigJson(payload.getConfigJson());
                boolean allowed = ConfigUpdateAccessPolicy.isAllowed(scope, player.hasPermissions(2),
                        context.server().isDedicatedServer(), MenuGateService.isEnabled(),
                        MenuGateService.isAllowed(player));
                if (!allowed) {
                    String message = scope == ConfigUpdateScope.FULL_MOD_MENU
                            ? "§c完整 Mod 菜单需要 OP2 和服务器后台单独授权"
                            : "§c你没有权限修改此背包设置（需要 OP2）";
                    player.sendSystemMessage(Component.literal(message));
                    return;
                }
                final String filteredJson;
                try {
                    filteredJson = ConfigUpdateAccessPolicy.filterConfigJson(scope, payload.getConfigJson());
                } catch (RuntimeException e) {
                    player.sendSystemMessage(Component.literal("§c配置更新被拒绝：JSON 根节点无效"));
                    return;
                }
                // 背包 scope 会先剥离无关区域，不能借由伪造完整 JSON 修改其他配置。
                boolean merged = ConfigManager.getInstance().mergeFromJsonString(filteredJson);
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
                LOGGER.info("玩家 {} 通过 {} 更新了服务端配置", player.getName().getString(), scope);
                // 同步最新的地图档案与预览图
                try {
                    ServerLevel overworld = context.server().overworld();
                    if (overworld != null) {
                        var configMaps = ConfigManager.getInstance().getModeMapVoteSettings().maps;
                        var profiles = com.habitrain.core.vote.MapVoteProfileStore.loadProfiles(
                                overworld, configMaps.keySet(), configMaps);
                        if (!profiles.isEmpty()) {
                            var profilePayloads = MapVoteProfilePayload.fragment(profiles);
                            for (ServerPlayer sp : context.server().getPlayerList().getPlayers()) {
                                for (var profilePayload : profilePayloads) {
                                    ServerPlayNetworking.send(sp, profilePayload);
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.debug("MapVoteProfile broadcast on ConfigUpdate skipped", t);
                }
                if (context.server().isSingleplayer()) return;
                // FullConfigSyncPayload 已含 global + tasks + gameModes + minigames + shader，
                // 单独的 TaskConfigPayload / ShaderConfigPayload 广播冗余，去掉（P1-16）。
                FullConfigSyncPayload.broadcastToAll(context.server());
            });
        });
        // C2S 地图介绍预览图：地图轮换/投票区域内，OP2 可上传到服务端世界目录。
        ServerPlayNetworking.registerGlobalReceiver(MapVotePreviewUploadPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) return;
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(Component.literal("§c预览图上传失败：需要 OP2 权限"));
                    return;
                }
                com.habitrain.core.vote.MapVoteProfileStore.UploadResult result =
                        com.habitrain.core.vote.MapVoteProfileStore.saveUploadedPreview(
                                player.serverLevel(), payload.mapId(),
                                payload.previousPreviewPath(), payload.pngBytes());
                if (result.success()) {
                    player.sendSystemMessage(Component.literal(
                            "§a地图 “" + payload.mapId() + "” 的预览图已上传并替换旧图"));
                    LOGGER.info("玩家 {} 上传地图预览图 map={} bytes={}",
                            player.getName().getString(), payload.mapId(), payload.pngBytes().length);
                    // 广播最新的 MapVoteProfilePayload 给所有在线玩家（包含上传者），使客户端即时刷新预览
                    try {
                        ServerLevel overworld = context.server().overworld();
                        if (overworld != null) {
                            var configMaps = ConfigManager.getInstance().getModeMapVoteSettings().maps;
                            var profiles = com.habitrain.core.vote.MapVoteProfileStore.loadProfiles(
                                    overworld, configMaps.keySet(), configMaps);
                            if (!profiles.isEmpty()) {
                                var profilePayloads = MapVoteProfilePayload.fragment(profiles);
                                for (ServerPlayer sp : context.server().getPlayerList().getPlayers()) {
                                    for (var profilePayload : profilePayloads) {
                                        ServerPlayNetworking.send(sp, profilePayload);
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        LOGGER.debug("MapVoteProfile broadcast on preview upload skipped", t);
                    }
                } else {
                    player.sendSystemMessage(Component.literal("§c预览图上传失败：" + result.message()));
                    LOGGER.warn("玩家 {} 上传地图预览图失败 map={} reason={}",
                            player.getName().getString(), payload.mapId(), result.message());
                }
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
        // C2S 角色扩展 v2 配置更新：完整 Mod Menu 的 OP2 + 门控授权后服务端权威应用。
        ServerPlayNetworking.registerGlobalReceiver(RoleConfigUpdatePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null || payload == null) {
                    return;
                }
                if (!ConfigUpdateAccessPolicy.isAllowed(ConfigUpdateScope.FULL_MOD_MENU,
                        player.hasPermissions(2), context.server().isDedicatedServer(),
                        MenuGateService.isEnabled(), MenuGateService.isAllowed(player))) {
                    player.sendSystemMessage(Component.literal("§c完整 Mod 菜单需要 OP2 和服务器后台单独授权"));
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
        // C2S 角色扩展握手本地 manifest 上报（audit P1-4）：服务端据此权威计算
        // §14.2 握手结果并门控角色动作；断线时在 LifecycleEventsRegistrar 清除。
        ServerPlayNetworking.registerGlobalReceiver(
                com.habitrain.core.network.RoleHandshakeReportPayload.TYPE, (payload, context) ->
                        context.server().execute(() -> {
                            ServerPlayer player = context.player();
                            if (player == null || payload == null) {
                                return;
                            }
                            com.habitrain.core.role.config.RoleHandshakeGate.INSTANCE
                                    .record(player.getUUID(), payload.toClientManifest());
                            LOGGER.debug("玩家 {} 上报角色扩展握手 manifest",
                                    player.getName().getString());
                        }));
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
