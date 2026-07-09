package com.habitrain.core.betel;

import betel.nut.component.BetelNutEntityComponents;
import com.habitrain.core.HabiTrainCore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class BetelQuestState {
    private static volatile BetelQuestState instance;

    private boolean revealUsedThisRound = false;

    private final ConcurrentMap<UUID, PlayerBetelData> playerData = new ConcurrentHashMap<>();

    private BetelQuestState() {}

    public static void init() {
        instance = new BetelQuestState();
    }

    public static BetelQuestState getInstance() {
        if (instance == null) {
            synchronized (BetelQuestState.class) {
                if (instance == null) {
                    instance = new BetelQuestState();
                }
            }
        }
        return instance;
    }

    private PlayerBetelData computePlayerData(UUID uuid) {
        return playerData.computeIfAbsent(uuid, k -> new PlayerBetelData());
    }

    public static PlayerBetelData getPlayerData(UUID uuid) {
        return getInstance().computePlayerData(uuid);
    }

    public static void markQuestAssigned(UUID uuid) {
        getPlayerData(uuid).hasBetelQuestBeenAssigned = true;
        HabiTrainCore.LOGGER.debug("玩家 {} 的槟榔任务已标记为本局已刷新", getPlayerName(uuid));
    }

    public static boolean hasQuestBeenAssigned(UUID uuid) {
        return getPlayerData(uuid).hasBetelQuestBeenAssigned;
    }

    public static boolean hasFoodRestriction(UUID uuid) {
        return getPlayerData(uuid).hasFoodRestriction;
    }

    public static void resetEatenStatus(Player player) {
        if (player == null) return;
        PlayerBetelData data = getPlayerData(player.getUUID());
        data.hasEatenBetelNut = false;

        try {
            var addiction = BetelNutEntityComponents.ADDICTION.get(player);
            long currentEatTime = addiction.getLastEatTime();
            data.lastDetectedEatTime = currentEatTime > 0 ? currentEatTime : 0;
        } catch (Exception e) {
            data.lastDetectedEatTime = 0;
        }

        HabiTrainCore.LOGGER.debug("玩家 {} 的吃槟榔状态已重置 (lastDetectedEatTime={})",
                player.getName().getString(), data.lastDetectedEatTime);
    }

    public static boolean hasPlayerEatenBetelNut(UUID uuid) {
        return getPlayerData(uuid).hasEatenBetelNut;
    }

    private static MinecraftServer getCurrentServer() {
        try {
            var gameInstance = net.fabricmc.loader.api.FabricLoader.getInstance().getGameInstance();
            if (gameInstance instanceof MinecraftServer server) {
                return server;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String getPlayerName(UUID uuid) {
        var server = getCurrentServer();
        if (server != null) {
            var player = server.getPlayerList().getPlayer(uuid);
            if (player != null) return player.getName().getString();
        }
        return uuid.toString();
    }

    /**
     * 重置全局状态（下一局新游戏前调用）。
     * <p>Package-private — 外部通过 {@link #resetGameState()} 调用。</p>
     */
    void resetAll() {
        revealUsedThisRound = false;
        playerData.clear();
    }

    /**
     * 公开的全局状态重置入口，用于游戏生命周期结束时的清理。
     */
    public static void resetGameState() {
        getInstance().resetAll();
    }

    public boolean isRevealUsed() {
        return revealUsedThisRound;
    }

    /**
     * Package-private — 仅在 betel 包内部使用（{@link BetelTickEngine}）。
     */
    void setRevealUsed(boolean used) {
        this.revealUsedThisRound = used;
    }

    // ──────────────────────────────────────────────
    // 状态枚举
    // ──────────────────────────────────────────────

    /**
     * 槟榔成瘾阶段，用于简化 {@code hasHeavyAddiction} 布尔判断。
     * <p>写入点：{@link BetelWithdrawal#applyHeavyAddictionEffects} 设为 {@code SEVERE}。
     * <br>清零点：{@link BetelTickEngine#tickPlayer} 中当底层成瘾阶段 &lt; 3 时设为 {@code NONE}。</p>
     */
    public enum AddictionStage {
        NONE,
        MILD,
        MODERATE,
        SEVERE,
        CRITICAL
    }

    /**
     * 槟榔效果应用状态，替代 {@code darknessAppliedThisTrigger} 与 {@code ateBetelNutToRelieve} 两个布尔字段。
     * <ul>
     *   <li>{@link #NONE} — 无效果。写入点：效果过期或触发重置时。
     *       <br>清零点：{@link BetelTickEngine} 中检测到成瘾阶段降低或吃槟榔解除。</li>
     *   <li>{@link #DARKNESS_APPLIED} — 黑暗效果已应用。
     *       <br>写入点：{@link BetelTickEngine#tickPlayer} 戒断值达到阈值时。</li>
     *   <li>{@link #WITHDRAWAL_ACTIVE} — 玩家刚吃槟榔以缓解戒断症状。
     *       <br>写入点：{@link BetelTickEngine#tickPlayer} 检测到吃槟榔时。</li>
     * </ul>
     */
    public enum EffectState {
        NONE,
        DARKNESS_APPLIED,
        WITHDRAWAL_ACTIVE
    }

    // ──────────────────────────────────────────────
    // 玩家数据
    // ──────────────────────────────────────────────

    public static class PlayerBetelData {
        /**
         * 本局是否已为玩家刷新槟榔任务。
         * <p>写入点：{@link #markQuestAssigned}。
         * <br>清零点：{@link #resetAll}（playerData 清空）。</p>
         */
        boolean hasBetelQuestBeenAssigned = false;

        /**
         * 上一 tick 的诊断阶段（仅 enableAddictionSystem = false 时使用）。
         * <p>写入点：{@link BetelTickEngine#tickPlayer} 自定义成瘾追踪。
         * <br>清零点：{@link #resetAll}（playerData 清空）。</p>
         */
        int lastDiagnosticStage = 0;

        /**
         * 玩家是否已在当前 tick 被首次处理（用于首次清除成瘾，防止残留）。
         * <p>写入点：{@link BetelTickEngine#tickPlayer} 首次进入时设为 true。
         * <br>清零点：{@link #resetAll}（playerData 清空）。</p>
         */
        boolean hasBeenProcessed = false;

        /**
         * 上一 tick 游戏是否未运行（用于检测游戏状态下降沿，避免重复清除）。
         * <p>写入点：{@link BetelTickEngine#tickPlayer} 检测到游戏停止时设为 true，恢复时设为 false。
         * <br>清零点：{@link #resetAll}（playerData 清空）。</p>
         */
        boolean wasGameNotRunning = false;

        /**
         * 上一 tick 玩家是否旁观（用于检测旁观进入/退出，避免重复清除）。
         * <p>写入点：{@link BetelTickEngine#tickPlayer} 检测到旁观时设为 true，退出旁观时设为 false。
         * <br>清零点：{@link #resetAll}（playerData 清空）。</p>
         */
        boolean wasSpectating = false;

        /**
         * 上次检测到的吃槟榔时间戳（来自 BetelNutAddictionComponent）。
         * <p>写入点：{@link #resetEatenStatus}、{@link BetelTickEngine#tickPlayer} 检测到吃槟榔时。
         * <br>清零点：{@link #resetEatenStatus}。</p>
         */
        long lastDetectedEatTime = 0;

        /**
         * 本局是否已检测到玩家吃过槟榔（用于任务完成判定）。
         * <p>写入点：{@link BetelTickEngine#tickPlayer} 检测到吃槟榔时设为 true。
         * <br>清零点：{@link #resetEatenStatus}。</p>
         */
        boolean hasEatenBetelNut = false;

        /**
         * 本局玩家吃过槟榔的次数。
         * <p>写入点：{@link BetelTickEngine#tickPlayer} 检测到吃槟榔时递增。
         * <br>清零点：{@link #resetAll}（playerData 清空）。</p>
         */
        int betelNutsEatenThisGame = 0;

        /**
         * 玩家上次吃槟榔的游戏时间（仅 enableAddictionSystem = false 时用于戒断计算）。
         * <p>写入点：{@link BetelTickEngine#tickPlayer} 检测到吃槟榔时。
         * <br>清零点：{@link #resetAll}（playerData 清空）。</p>
         */
        long ownLastEatGameTime = 0;

        /**
         * 当前成瘾阶段，替代 {@code hasHeavyAddiction} 布尔字段。
         * <p>写入点：{@link BetelWithdrawal#applyHeavyAddictionEffects}（设为 SEVERE）。
         * <br>清零点：{@link BetelTickEngine#tickPlayer} 中成瘾阶段下降时（设为 NONE）。</p>
         */
        AddictionStage addictionStage = AddictionStage.NONE;

        /**
         * 当前效果应用状态，替代 {@code darknessAppliedThisTrigger} 与 {@code ateBetelNutToRelieve}。
         * <p>写入点：{@link BetelTickEngine#tickPlayer}（DARKNESS_APPLIED 或 WITHDRAWAL_ACTIVE）。
         * <br>清零点：{@link BetelTickEngine#tickPlayer} 中效果过期或解除时（设为 NONE）。</p>
         */
        EffectState effectState = EffectState.NONE;

        /**
         * 玩家是否因槟榔成瘾而无法吃普通食物（Stage >= 3 时激活）。
         * <p>写入点：{@link BetelTickEngine#tickPlayer} 达到 Stage 3 时设为 true。
         * <br>清零点：{@link #resetAll}（playerData 清空）。</p>
         */
        boolean hasFoodRestriction = false;
    }
}
