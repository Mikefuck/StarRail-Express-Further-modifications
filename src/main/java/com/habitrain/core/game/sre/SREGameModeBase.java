package com.habitrain.core.game.sre;

import com.habitrain.core.api.*;
import com.habitrain.core.config.ConfigManager;
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

/**
 * SRE 游戏模式公共基类。
 * 所有 SRE 相关的语音群组、原版任务注册、游戏事件处理集中在此。
 */
public abstract class SREGameModeBase extends AbstractGameMode {

    private static final Logger LOGGER = LoggerFactory.getLogger("SREGameModeBase");
    private static final String CORE_MOD_ID = "habitrain_core";

    // ========== Converged static state via service object ==========
    private static final SREGameModeState STATE = new SREGameModeState();

    private static final UUID LOBBY_GROUP_ID = UUID.randomUUID();
    private static final int MAX_VOICE_JOIN_RETRIES = 400; // 每 tick 重试一次，约 20 秒（慢客户端留足握手时间）
    /** 大厅语音群组巡检间隔（tick）。JOIN 入队失败/重试耗尽后靠此补拉。 */
    private static final int LOBBY_RECONCILE_INTERVAL_TICKS = 100; // 5 秒
    private static int lobbyReconcileTickCounter = 0;

    protected final List<TaskCategory> taskCategories = new ArrayList<>();


    protected SREGameModeBase() {
        registerSREEvents();
        registerBuiltinTasksOnce();
    }

    // ========== 原版任务注册 ==========

    /**
     * 按 {@link SreTaskMirrors} 的单一真相表登记原版 SRE 任务的「配置镜像」。
     *
     * <p><b>这里只登记元数据，不派发、也不重做原版逻辑</b>：原版任务的实际派发、加权、
     * 完成判定、透视渲染全部由上游 {@code SREPlayerTaskComponent} / {@code TaskBlockOverlayRenderer}
     * 负责（本 mod 只在 {@code GenerateTaskMixin} 里把 DLC 任务并进同一个加权抽取）。
     * 镜像的作用是给每个原版任务一个 {@code habitrain_core:<id>} 的稳定配置键，
     * 使下游/服主可以在 ModMenu 任务配置页里对<b>上游任务</b>独立设置：
     * <ul>
     *   <li>{@code enabled} —— 单独禁用某个原版任务；</li>
     *   <li>{@code mapFilterMode / enabledMaps} —— 只在指定地图启用；</li>
     *   <li>{@code refreshWeight} —— 刷新权重；</li>
     *   <li>{@code instinctColor / outlineWidth} —— 透视颜色与描边（按
     *       {@link SreTaskMirrors#blockTypeId()} 命中上游 {@code GameUtils.taskBlocks} 的类型号）；</li>
     * </ul>
     *
     * <p>所有镜像一律 {@code poolEligible(false)}：它们没有 {@code onTick} /
     * {@code completionChecker}，一旦进入 DLC 加权池就会「分配即完成」。
     */
    private static void registerBuiltinTasksOnce() {
        if (STATE.isBuiltinTasksRegistered()) return;
        STATE.setBuiltinTasksRegistered(true);

        for (SreTaskMirrors mirror : SreTaskMirrors.all()) {
            registerBuiltin(mirror.id(), mirror.displayName(), mirror.category(),
                    mirror.weight(), mirror.blockTypeId());
        }

        LOGGER.info("已注册 {} 个内置SRE任务镜像", SreTaskMirrors.all().size());
    }

    /**
     * 登记一个「原版 SRE 任务镜像」定义。
     * <p>这些定义是空壳（无 onTick / completionChecker），登记进 {@link TaskRegistry}
     * 只为让原版任务能以 {@code habitrain_core:<id>} 参与配置与查询；
     * 必须以 {@code poolEligible(false)} 显式排除出 DLC 派发池，否则会被
     * {@code DlcTaskPoolBuilder} 当成普通 DLC 任务派发（分配即完成）。
     */
    private static void registerBuiltin(String id, String displayName, TaskCategory category,
                                         float weight, int blockTypeId) {
        TaskRegistry.register(new TaskDefinition.Builder(CORE_MOD_ID, id)
                .displayName(displayName)
                .category(category)
                .gameMode("sre:base")
                .weight(weight)
                .blockTypeId(blockTypeId)
                .poolEligible(false)
                .build()
        );
    }

    // ========== SRE 事件注册 ==========

    private void registerSREEvents() {
        if (STATE.isSreEventsRegistered()) return;
        STATE.setSreEventsRegistered(true);
        OnGameStarted.EVENT.register(serverLevel -> {
            // 清空待入队：对局已开始不再把人拉进大厅群
            if (!STATE.getPendingVoiceJoins().isEmpty()) {
                STATE.getPendingVoiceJoins().clear();
                LOGGER.info("[VoiceGroup] 游戏开始，已清理待加入语音群组的队列");
            }
            // 已在 LobbyChat 的在线玩家离开大厅群（对局中不应停留在大厅语音）
            leaveLobbyGroupForAllOnline(serverLevel.getServer());
        });

        OnGameEnd.EVENT.register((serverLevel, gameWorldComponent) -> {
            STATE.setPendingGameEndGroupJoin(true);
            LOGGER.info("[VoiceGroup] 游戏结束，标记待处理");
        });
    }

    // ========== 语音群组管理 ==========

    /**
     * 玩家加入大厅语音群组的重试队列。
     * 当玩家加入世界时无活跃游戏对局，将其加入队列等待 voicechat 连接就绪。
     * 大厅语音群组开关关闭时不入队（不产生任何拉入行为）。
     */
    public static void queueLobbyGroupJoin(MinecraftServer server, UUID playerUUID) {
        // 审核 B25：显式的可选依赖守卫，避免在未安装 voicechat 时靠 try/catch 兜底。
        if (!VoiceChatPresence.isLoaded()) {
            return;
        }
        if (!ConfigManager.getInstance().isLobbyVoiceGroupEnabled()) return;
        STATE.getPendingVoiceJoins().put(playerUUID, MAX_VOICE_JOIN_RETRIES);
        LOGGER.info("[VoiceGroup] queued {} for lobby group join", playerUUID);
    }

    /**
     * 尝试将玩家加入大厅语音群组。
     * @return true 表示成功加入 / 已在大厅群 / 无需再试，false 表示需要重试
     */
    private static boolean tryAddPlayerToLobbyGroup(MinecraftServer server, UUID playerUUID) {
        if (!VoiceChatPresence.isLoaded()) {
            return false;
        }
        if (!ConfigManager.getInstance().isLobbyVoiceGroupEnabled()) return false;
        if (TrainVoicePlugin.isVoiceChatMissing()) return false;
        if (TrainVoicePlugin.SERVER_API == null) return false;

        VoicechatServerApi api = TrainVoicePlugin.SERVER_API;
        VoicechatConnection connection = api.getConnectionOf(playerUUID);
        if (connection == null) return false;

        try {
            // 已在 LobbyChat → 视为成功，避免重复 setGroup
            Group current = connection.getGroup();
            if (current != null && LOBBY_GROUP_ID.equals(current.getId())) {
                return true;
            }

            if (STATE.getLobbyGroup() == null) {
                STATE.setLobbyGroup(api.groupBuilder()
                        .setId(LOBBY_GROUP_ID)
                        .setName("LobbyChat")
                        .setPersistent(true)
                        .setType(Group.Type.OPEN)
                        .setHidden(false)
                        .build());
            }
            connection.setGroup(STATE.getLobbyGroup());
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
            } catch (Exception e) {
                LOGGER.debug("isAnySreGameRunning failed: {}", e.getMessage());
            }
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
            } catch (Exception e) {
                LOGGER.debug("isAnySreGameStartingOrRunning failed: {}", e.getMessage());
            }
        }
        return false;
    }

    private static void leaveLobbyGroupForAllOnline(MinecraftServer server) {
        if (!VoiceChatPresence.isLoaded()) {
            return;
        }
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

    /**
     * 应用大厅语音群组开关（配置保存/同步后调用）。
     * 关闭时立即把所有在线玩家移出 LobbyChat，避免已加入的玩家停留在群组内。
     */
    public static void applyLobbyGroupToggle(MinecraftServer server) {
        if (server == null) return;
        if (!ConfigManager.getInstance().isLobbyVoiceGroupEnabled()) {
            leaveLobbyGroupForAllOnline(server);
        }
    }

    /** 断线时立刻从 pending 队列移除（不必等下一 tick 扫描）。 */
    public static void removePendingVoiceJoin(UUID playerUUID) {
        if (playerUUID == null) return;
        STATE.getPendingVoiceJoins().remove(playerUUID);
    }

    public static void processPendingVoiceJoins(MinecraftServer server) {
        if (STATE.getPendingVoiceJoins().isEmpty()) return;
        // 大厅语音群组开关关闭：清空遗留队列，不产生任何拉入行为
        if (!ConfigManager.getInstance().isLobbyVoiceGroupEnabled()) {
            STATE.getPendingVoiceJoins().clear();
            return;
        }
        // 对局已进入 STARTING/ACTIVE/STOPPING：不再把任何人拉进大厅群，直接清空队列
        if (isAnySreGameStartingOrRunning(server)) {
            STATE.getPendingVoiceJoins().clear();
            LOGGER.info("[VoiceGroup] cleared pending queue (game starting/running)");
            return;
        }
        // voicechat 服务端 API 尚未就绪时不消耗重试次数（否则 20 秒后永久放弃，直到对局结束才补拉）
        if (TrainVoicePlugin.isVoiceChatMissing() || TrainVoicePlugin.SERVER_API == null) {
            return;
        }
        Iterator<Map.Entry<UUID, Integer>> it = STATE.getPendingVoiceJoins().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            UUID playerId = entry.getKey();

            // 玩家离线 → 移除
            if (server.getPlayerList().getPlayer(playerId) == null) {
                it.remove();
                LOGGER.info("[VoiceGroup] removed {} from pending queue (offline)", playerId);
                continue;
            }

            // 重试次数耗尽 → 暂移出队列；reconcileLobbyGroupMembership 会在大厅阶段周期性补入
            if (entry.getValue() <= 0) {
                it.remove();
                LOGGER.warn("[VoiceGroup] temporarily removed {} from pending queue (retries exhausted; will requeue via lobby reconcile)", playerId);
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
        if (!STATE.isPendingGameEndGroupJoin()) return;
        STATE.setPendingGameEndGroupJoin(false);

        // 大厅语音群组开关关闭：对局结束后不再把玩家拉入大厅语音群组
        if (!ConfigManager.getInstance().isLobbyVoiceGroupEnabled()) {
            LOGGER.info("[VoiceGroup] 大厅语音群组已关闭，跳过对局结束入队");
            return;
        }

        // 等待对局完全结束（没有运行中的 SRE 游戏）
        if (isAnySreGameRunning(server)) {
            STATE.setPendingGameEndGroupJoin(true); // 下一 tick 再试
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

    /**
     * 大厅阶段周期性巡检：把尚未进入 LobbyChat 的在线玩家重新入队。
     * <p>
     * 修复场景：玩家 JOIN 时 voicechat 连接尚未建立，pending 队列在 ~20s 内重试耗尽后
     * 被移除，之后永远不会再进大厅群，只有对局结束的 processGameEndGroupJoin 才会补拉。
     * 本方法保证大厅空闲期间持续补拉。
     */
    public static void reconcileLobbyGroupMembership(MinecraftServer server) {
        if (!VoiceChatPresence.isLoaded()) {
            return;
        }
        if (server == null) return;
        if (!ConfigManager.getInstance().isLobbyVoiceGroupEnabled()) return;
        lobbyReconcileTickCounter++;
        if (lobbyReconcileTickCounter < LOBBY_RECONCILE_INTERVAL_TICKS) return;
        lobbyReconcileTickCounter = 0;

        // 对局中/开局过渡：不拉人进大厅
        if (isAnySreGameStartingOrRunning(server)) return;
        // voicechat 未就绪：等下次
        if (TrainVoicePlugin.isVoiceChatMissing() || TrainVoicePlugin.SERVER_API == null) return;

        VoicechatServerApi api = TrainVoicePlugin.SERVER_API;
        int queued = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            // 已在 pending 队列中 → 由 processPendingVoiceJoins 处理
            if (STATE.getPendingVoiceJoins().containsKey(id)) continue;

            try {
                VoicechatConnection connection = api.getConnectionOf(id);
                if (connection != null) {
                    Group group = connection.getGroup();
                    if (group != null && LOBBY_GROUP_ID.equals(group.getId())) {
                        continue; // 已在 LobbyChat
                    }
                }
                // connection 未就绪，或不在 LobbyChat → 入队
                STATE.getPendingVoiceJoins().put(id, MAX_VOICE_JOIN_RETRIES);
                queued++;
            } catch (Exception e) {
                LOGGER.debug("[VoiceGroup] lobby reconcile failed for {}: {}", id, e.getMessage());
            }
        }
        if (queued > 0) {
            LOGGER.info("[VoiceGroup] lobby reconcile queued {} player(s) missing LobbyChat", queued);
        }
    }

    // ========== 游戏结束清理 ==========


    @Override
    public void onEnd(ServerLevel level, WinResult result) {
        // 游戏结束 → 清空当前维度活跃 DLC 任务
        TaskManager.getInstance().clearActiveTasksForLevel(level.dimension());
    }

    @Override
    public void onCleanup(ServerLevel level) {
        // 清理现场时也确保当前维度活跃任务被清空
        TaskManager.getInstance().clearActiveTasksForLevel(level.dimension());
    }
}
