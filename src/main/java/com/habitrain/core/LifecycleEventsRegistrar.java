package com.habitrain.core;

import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.betel.BetelLeafHandler;
import com.habitrain.core.betel.BetelQuestState;
import com.habitrain.core.game.blackout.BlackoutExileVoteManager;
import com.habitrain.core.game.blackout.BlackoutPoliceHireService;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutSheriffVoteManager;
import com.habitrain.core.game.blackout.BlackoutShopService;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import com.habitrain.core.game.sre.SREGameModeBase;
import com.habitrain.core.misc.EffectOwnershipTracker;
import com.habitrain.core.network.CustomTaskBlockPayload;
import com.habitrain.core.network.FullConfigSyncPayload;
import com.habitrain.core.network.ShaderConfigPayload;
import com.habitrain.core.network.TaskConfigPayload;
import com.habitrain.core.task.BackpackQuestState;
import com.habitrain.core.task.BackpackSearchHandler;
import com.habitrain.core.task.SlownessReapplyManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 生命周期事件注册器 — 负责注册 SERVER_STARTED / SERVER_STOPPING / JOIN / DISCONNECT 事件。
 * <p>在 {@link HabiTrainCore#onInitialize()} 中调用 {@link #init()}。</p>
 */
public final class LifecycleEventsRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|LifecycleEventsRegistrar");

    private LifecycleEventsRegistrar() {}

    public static void init() {
        // 服务器启动后加载配置
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ConfigManager.getInstance().load();
            ConfigManager.getInstance().setServer(server);
            ConfigManager.getInstance().applyMinigameEnforcement(server);
            // 所有 entrypoint（含本 mod 与依赖 DLC）已在此前完成注册，
            // 现在冻结注册表，禁止运行期注册导致 CME 与状态不一致。
            TaskRegistry.freeze();
            GameModeRegistry.freeze();
            LOGGER.info("配置已加载，共 {} 个已注册任务（注册表已冻结）", TaskRegistry.size());
        });
        // 服务器关闭时清理停电模式各 manager 的 per-level 静态 Map 条目。
        // 单机模式下集成服务器停止后客户端 JVM 仍存活，static 字段不会重置，
        // 不清理会导致下一局残留状态（计时器/角色/商店/投票）误用。
        // 注：fabric-api 此版本无 ServerLevelEvents.UNLOAD，故在 SERVER_STOPPING 遍历所有 level 清理。
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ConfigManager.getInstance().setServer(null);
            for (ServerLevel level : server.getAllLevels()) {
                if (GameModeRegistry.isActiveInLevel(level)) {
                    GameModeRegistry.stop(level);
                }
                BlackoutRoleManager.clear(level);
                BlackoutTimerSystem.reset(level);
                BlackoutSheriffVoteManager.reset(level);
                BlackoutShopService.resetRound(level);
                BlackoutPoliceHireService.cleanup(level);
                BlackoutExileVoteManager.reset(level);
            }
            // 清理所有跨局残留状态
            SlownessReapplyManager.clearAll();
            BetelLeafHandler.clearAllHarvests();
            BackpackSearchHandler.clearAllSearches();
            com.habitrain.core.game.blackout.task.AddCoalHandler.clearAll();
            com.habitrain.core.game.blackout.task.FurnaceExplosionHandler.clearAll();
            com.habitrain.core.game.blackout.task.MaintainPowerHandler.clearAll();
            com.habitrain.core.game.blackout.task.RestorePowerHandler.clearAll();
            com.habitrain.core.game.blackout.task.RepairWiringHandler.clearAll();
            com.habitrain.core.misc.EffectOwnershipTracker.clearAll();
            BetelQuestState.resetGameState();
            BackpackQuestState.getInstance().resetAll();
        });
        // 玩家加入
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            try {
                // 如果当前没有 SRE 对局运行 → 入队等待加入大厅语音群组
                if (!SREGameModeBase.isAnySreGameStartingOrRunning(server)) {
                    SREGameModeBase.queueLobbyGroupJoin(server, player.getUUID());
                }
                // 如果有对局运行，不入队（避免把游戏中的玩家拉进大厅群组）
            } catch (Exception e) {
                LOGGER.error("[VoiceGroup] 处理语音群组加入失败", e);
            }
            // 同步配置
            TaskConfigPayload.sendToPlayer(player);
            CustomTaskBlockPayload.sendToPlayer(player);
            ShaderConfigPayload.sendToPlayer(player);
            // 完整配置同步（global + tasks + gameModes + minigames）：让客户端显示服务端真实值，
            // 避免 OP 联机保存时用本地过期全局项覆盖服务端。
            FullConfigSyncPayload.sendToPlayer(player);
            // 通知激活的 GameMode 玩家加入
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level != null) {
                GameModeRegistry.getActiveForLevel(level)
                    .ifPresent(mode -> mode.onPlayerJoin(player));
            }
        });
        // 玩家断线：通知激活的 GameMode 处理。
        // 停电模式据此把断线玩家移出存活阵营，避免其继续被计为放逐候选人或卡住胜负判定（Q8）。
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player == null) return;
            try {
                // 清除汽笛确认窗口（断线后无需保留）
                com.habitrain.core.game.blackout.BlackoutHornVoteHandler.onPlayerRemoved(player.getUUID());
                // 立刻从语音 pending 队列移除（不必等下一 tick）
                SREGameModeBase.removePendingVoiceJoin(player.getUUID());
                // 清除效果归属追踪数据
                EffectOwnershipTracker.clearPlayer(player.getUUID());
                ServerLevel disconnectLevel = player.serverLevel();
                if (disconnectLevel != null) {
                    GameModeRegistry.getActiveForLevel(disconnectLevel)
                        .ifPresent(mode -> mode.onPlayerLeave(player));
                }
            } catch (Exception e) {
                LOGGER.error("[GameMode] 处理玩家断线失败", e);
            }
        });
    }
}
