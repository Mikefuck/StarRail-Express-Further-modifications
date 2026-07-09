package com.habitrain.core.game.sre;

import com.habitrain.core.api.*;
import com.habitrain.core.game.AbstractGameMode;
import com.habitrain.core.task.TaskManager;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import io.wifi.starrailexpress.compat.TrainVoicePlugin;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameStarted;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SRE 游戏模式公共基类。
 * 所有 SRE 相关的语音群组、原版任务注册、游戏事件处理集中在此。
 */
public abstract class SREGameModeBase extends AbstractGameMode {

    private static final Logger LOGGER = LoggerFactory.getLogger("SREGameModeBase");
    private static final String CORE_MOD_ID = "habitrain_core";

    // 大厅语音群组
    private static Group LOBBY_GROUP = null;
    private static final UUID LOBBY_GROUP_ID = UUID.randomUUID();
    private static final Map<UUID, Integer> pendingVoiceJoins = new ConcurrentHashMap<>();
    private static final int MAX_VOICE_JOIN_RETRIES = 400; // 每 tick 重试一次，约 20 秒（慢客户端留足握手时间）

    // 游戏结束语音群组恢复
    private static boolean pendingGameEndGroupJoin = false;

    // 原版任务注册保护（防双重重叠）
    private static boolean builtinTasksRegistered = false;
    // SRE 事件注册保护（SREMurderMode + SRERepairMode 构造时各调一次 super()，防重复注册监听器）
    private static boolean sreEventsRegistered = false;

    protected final List<TaskCategory> taskCategories = new ArrayList<>();

    protected SREGameModeBase() {
        registerSREEvents();
        registerBuiltinTasksOnce();
    }

    // ========== 原版任务注册 ==========

    private static void registerBuiltinTasksOnce() {
        if (builtinTasksRegistered) return;
        builtinTasksRegistered = true;

        // Murder mode tasks
        registerBuiltin("sleep", "睡觉", TaskCategory.MURDER, 1.0f, 4);
        registerBuiltin("eat", "进食", TaskCategory.MURDER, 1.0f, 1);
        registerBuiltin("drink", "喝水", TaskCategory.MURDER, 1.0f, 2);
        registerBuiltin("exercise", "锻炼", TaskCategory.MURDER, 1.0f, 5);
        registerBuiltin("raed_book", "阅读", TaskCategory.MURDER, 1.0f, 6);
        registerBuiltin("bathe", "洗澡", TaskCategory.MURDER, 1.0f, 3);
        registerBuiltin("toilet", "上厕所", TaskCategory.MURDER, 1.0f, 8);
        registerBuiltin("chair", "坐椅子", TaskCategory.MURDER, 1.0f, 9);
        registerBuiltin("note_block", "音符盒", TaskCategory.MURDER, 1.0f, 10);
        registerBuiltin("meditate", "冥想", TaskCategory.MURDER, 1.0f, -1);
        registerBuiltin("outside", "外出", TaskCategory.MURDER, 1.0f, -1);
        registerBuiltin("breathe", "呼吸新鲜空气", TaskCategory.MURDER, 1.0f, -1);
        registerBuiltin("be_alone", "一个人静静", TaskCategory.MURDER, 1.0f, -1);

        // Repair mode tasks
        registerBuiltin("repair_wire", "修复线路", TaskCategory.REPAIR, 1.0f, -1);
        registerBuiltin("repair_panel", "修复面板", TaskCategory.REPAIR, 1.0f, -1);

        // Shared tasks
        registerBuiltin("vending_machine", "售货机", TaskCategory.ALL, 0.5f, 11);

        LOGGER.info("已注册 {} 个内置SRE任务", TaskRegistry.size());
    }

    private static void registerBuiltin(String id, String displayName, TaskCategory category,
                                         float weight, int blockTypeId) {
        TaskRegistry.register(new TaskDefinition.Builder(CORE_MOD_ID, id)
                .displayName(displayName)
                .category(category)
                .gameMode("sre:base")
                .weight(weight)
                .blockTypeId(blockTypeId)
                .build()
        );
    }

    // ========== SRE 事件注册 ==========

    private void registerSREEvents() {
        if (sreEventsRegistered) return;
        sreEventsRegistered = true;
        OnGameStarted.EVENT.register(serverLevel -> {
            // 清空待入队：对局已开始不再把人拉进大厅群
            if (!pendingVoiceJoins.isEmpty()) {
                pendingVoiceJoins.clear();
                LOGGER.info("[VoiceGroup] 游戏开始，已清理待加入语音群组的队列");
            }
            // 已在 LobbyChat 的在线玩家离开大厅群（对局中不应停留在大厅语音）
            leaveLobbyGroupForAllOnline(serverLevel.getServer());
        });

        OnGameEnd.EVENT.register((serverLevel, gameWorldComponent) -> {
            pendingGameEndGroupJoin = true;
            LOGGER.info("[VoiceGroup] 游戏结束，标记待处理");
        });
    }

    // ========== 语音群组管理 ==========

    /**
     * 玩家加入大厅语音群组的重试队列。
     * 当玩家加入世界时无活跃游戏对局，将其加入队列等待 voicechat 连接就绪。
     */
    public static void queueLobbyGroupJoin(MinecraftServer server, UUID playerUUID) {
        pendingVoiceJoins.put(playerUUID, MAX_VOICE_JOIN_RETRIES);
        LOGGER.info("[VoiceGroup] queued {} for lobby group join", playerUUID);
    }

    /**
     * 尝试将玩家加入大厅语音群组。
     * @return true 表示成功加入或不需要加入（不在队列中），false 表示需要重试
     */
    private static boolean tryAddPlayerToLobbyGroup(MinecraftServer server, UUID playerUUID) {
        if (TrainVoicePlugin.isVoiceChatMissing()) return false;
        if (TrainVoicePlugin.SERVER_API == null) return false;

        VoicechatServerApi api = TrainVoicePlugin.SERVER_API;
        VoicechatConnection connection = api.getConnectionOf(playerUUID);
        if (connection == null) return false;

        try {
            if (LOBBY_GROUP == null) {
                LOBBY_GROUP = api.groupBuilder()
                        .setId(LOBBY_GROUP_ID)
                        .setName("LobbyChat")
                        .setPersistent(true)
                        .setType(Group.Type.OPEN)
                        .setHidden(false)
                        .build();
            }
            connection.setGroup(LOBBY_GROUP);
            LOGGER.info("[VoiceGroup] successfully added {} to lobby group", playerUUID);
            return true;
        } catch (Exception e) {
            LOGGER.error("[VoiceGroup] failed to set group for player {}", playerUUID, e);
            return false;
        }
    }

    /**
     * 检查服务器上当前是否有任何 SRE 对局正在运行（ACTIVE/STOPPING）。
     * 用于 JOIN 事件判断是否应将玩家加入队列。
     */
    public static boolean isAnySreGameRunning(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            try {
                var gameWorld = SREGameWorldComponent.KEY.get(level);
                if (gameWorld != null && gameWorld.isRunning()) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * 检查是否有 SRE 对局处于 STARTING / ACTIVE / STOPPING。
     * STARTING 阶段 isRunning()=false，但已不应对新玩家拉进大厅群。
     */
    public static boolean isAnySreGameStartingOrRunning(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            try {
                var gameWorld = SREGameWorldComponent.KEY.get(level);
                if (gameWorld == null) continue;
                var status = gameWorld.getGameStatus();
                if (status == SREGameWorldComponent.GameStatus.STARTING
                        || status == SREGameWorldComponent.GameStatus.ACTIVE
                        || status == SREGameWorldComponent.GameStatus.STOPPING) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    /** 让所有在线玩家离开 LobbyChat（对局开始时调用）。connection 未就绪则跳过。 */
    private static void leaveLobbyGroupForAllOnline(MinecraftServer server) {
        if (server == null) return;
        if (TrainVoicePlugin.isVoiceChatMissing() || TrainVoicePlugin.SERVER_API == null) return;
        VoicechatServerApi api = TrainVoicePlugin.SERVER_API;
        int left = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                VoicechatConnection connection = api.getConnectionOf(player.getUUID());
                if (connection == null) continue;
                Group group = connection.getGroup();
                if (group != null && LOBBY_GROUP_ID.equals(group.getId())) {
                    connection.setGroup(null);
                    left++;
                }
            } catch (Exception e) {
                LOGGER.debug("[VoiceGroup] failed to leave lobby group for {}", player.getUUID(), e);
            }
        }
        if (left > 0) {
            LOGGER.info("[VoiceGroup] removed {} players from LobbyChat on game start", left);
        }
    }

    /** 断线时立刻从 pending 队列移除（不必等下一 tick 扫描）。 */
    public static void removePendingVoiceJoin(UUID playerUUID) {
        if (playerUUID == null) return;
        pendingVoiceJoins.remove(playerUUID);
    }

    public static void processPendingVoiceJoins(MinecraftServer server) {
        if (pendingVoiceJoins.isEmpty()) return;
        // 对局已进入 STARTING/ACTIVE/STOPPING：不再把任何人拉进大厅群，直接清空队列
        if (isAnySreGameStartingOrRunning(server)) {
            pendingVoiceJoins.clear();
            LOGGER.info("[VoiceGroup] cleared pending queue (game starting/running)");
            return;
        }
        Iterator<Map.Entry<UUID, Integer>> it = pendingVoiceJoins.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            UUID playerId = entry.getKey();

            // 玩家离线 → 移除
            if (server.getPlayerList().getPlayer(playerId) == null) {
                it.remove();
                LOGGER.info("[VoiceGroup] removed {} from pending queue (offline)", playerId);
                continue;
            }

            // 重试次数耗尽 → 移除并记录日志
            if (entry.getValue() <= 0) {
                it.remove();
                LOGGER.warn("[VoiceGroup] removed {} from pending queue (retries exhausted)", playerId);
                continue;
            }

            // 尝试加入
            if (tryAddPlayerToLobbyGroup(server, playerId)) {
                it.remove();
            } else {
                entry.setValue(entry.getValue() - 1);
            }
        }
    }

    public static void processGameEndGroupJoin(MinecraftServer server) {
        if (!pendingGameEndGroupJoin) return;
        pendingGameEndGroupJoin = false;

        // 等待对局完全结束（没有运行中的 SRE 游戏）
        if (isAnySreGameRunning(server)) {
            pendingGameEndGroupJoin = true; // 下一 tick 再试
            return;
        }

        // 对局结束后把所有在线玩家入队等待加入大厅语音群组。
        // 不再用 isEmpty() 门：若新玩家在对局结束同 tick JOIN 已先入队，此处对其余在线玩家补入队；
        // queueLobbyGroupJoin 的 put 幂等（刷新重试计数），不会产生重复条目。
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            queueLobbyGroupJoin(server, player.getUUID());
        }
        LOGGER.info("[VoiceGroup] queued all online players for lobby group join after game end");
    }

    // ========== 游戏结束清理 ==========

    @Override
    public void onEnd(ServerLevel level, WinResult result) {
        // 游戏结束 → 清空所有活跃 DLC 任务
        TaskManager.getInstance().clearAllActiveTasks();
    }

    @Override
    public void onCleanup(ServerLevel level) {
        // 清理现场时也确保活跃任务被清空
        TaskManager.getInstance().clearAllActiveTasks();
    }
}
