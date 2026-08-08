package com.habitrain.core.game.sre;

import com.habitrain.core.network.GameEndTransitionPayload;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import io.wifi.starrailexpress.index.TMMItems;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 对局结束结算画面（GameEndTransitionScreen）的服务端协调器。
 * <p>
 * 在 SRE {@code GameStatus.STOPPING} 时广播结算载荷；等待环境（局后天气/时间）
 * 应用完成后二次广播，客户端据此推进画面。同时负责按 MVP 阵营设置其手持道具。
 */
public final class GameEndTransitionCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|GameEndTransitionCoordinator");

    private static final ConcurrentMap<ResourceKey<net.minecraft.world.level.Level>, Boolean> NOTIFIED = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ResourceKey<net.minecraft.world.level.Level>, Long> ENVIRONMENT_READY_AT = new ConcurrentHashMap<>();

    private GameEndTransitionCoordinator() {}

    /** SRE 游戏状态进入 STOPPING：广播结算载荷（去重）。 */
    public static void onStatusStopping(ServerLevel level) {
        if (level == null) return;
        ResourceKey<net.minecraft.world.level.Level> dim = level.dimension();
        if (NOTIFIED.putIfAbsent(dim, Boolean.TRUE) != null) return;
        try {
            applyMvpItem(level);
            broadcast(level, false);
        } catch (Throwable t) {
            NOTIFIED.remove(dim);
            LOGGER.warn("[GameEndTransition] broadcast failed dim={}", dim.location(), t);
        }
    }

    /** 局后环境（天气/时间）已应用：记录就绪时间戳，等待天气同步后二次广播。 */
    public static void onEnvironmentReady(ServerLevel level) {
        if (level == null) return;
        ResourceKey<net.minecraft.world.level.Level> dim = level.dimension();
        if (!NOTIFIED.containsKey(dim)) return;
        if (ENVIRONMENT_READY_AT.putIfAbsent(dim, level.getGameTime() + 2) != null) return;
        LOGGER.info("[GameEndTransition] environment applied dim={} — waiting for weather sync", dim.location());
    }

    /** 每 tick 推进：环境就绪后广播 environmentReady=true 的载荷。 */
    public static void tick(MinecraftServer server) {
        if (server == null || ENVIRONMENT_READY_AT.isEmpty()) return;
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<net.minecraft.world.level.Level> dim = level.dimension();
            Long readyAt = ENVIRONMENT_READY_AT.get(dim);
            if (readyAt == null) continue;
            if (level.getGameTime() < readyAt) continue;
            try {
                broadcast(level, true);
                ENVIRONMENT_READY_AT.remove(dim, readyAt);
                NOTIFIED.remove(dim);
            } catch (Throwable t) {
                ENVIRONMENT_READY_AT.replace(dim, readyAt, level.getGameTime() + 20);
                LOGGER.warn("[GameEndTransition] environment-ready broadcast failed dim={}", dim.location(), t);
            }
        }
    }

    /** 离开 STOPPING（游戏真正结束）：若环境尚未就绪则清理通知标记。 */
    public static void onStatusLeavingStopping(ServerLevel level) {
        if (level == null) return;
        ResourceKey<net.minecraft.world.level.Level> dim = level.dimension();
        if (!ENVIRONMENT_READY_AT.containsKey(dim)) {
            NOTIFIED.remove(dim);
        }
    }

    public static void resetAll() {
        NOTIFIED.clear();
        ENVIRONMENT_READY_AT.clear();
    }

    private static void broadcast(ServerLevel level, boolean environmentReady) {
        SREGameRoundEndComponent roundEnd = SREGameRoundEndComponent.KEY.get(level);
        if (roundEnd == null) return;
        GameUtils.WinStatus winStatus = roundEnd.getWinStatus();
        if (winStatus == null || winStatus == GameUtils.WinStatus.NONE || winStatus == GameUtils.WinStatus.NOT_MODIFY) {
            return;
        }
        GameEndTransitionPayload payload = buildPayload(level, roundEnd, winStatus, environmentReady);
        for (ServerPlayer player : level.players()) {
            GameEndTransitionPayload.sendTo(player, payload);
        }
        LOGGER.info("[GameEndTransition] broadcast dim={} winStatus={} environmentReady={} players={}",
                level.dimension().location(), winStatus, environmentReady, level.players().size());
    }

    private static GameEndTransitionPayload buildPayload(ServerLevel level, SREGameRoundEndComponent roundEnd,
                                                         GameUtils.WinStatus winStatus, boolean environmentReady) {
        String modeId = "";
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game != null && game.getGameMode() != null && game.getGameMode().identifier != null) {
                modeId = game.getGameMode().identifier.toString();
            }
        } catch (Throwable ignored) {}

        boolean isCustomComponent = winStatus == GameUtils.WinStatus.CUSTOM_COMPONENT;
        boolean isCustom = winStatus == GameUtils.WinStatus.CUSTOM;

        String customTitleJson = "";
        if (isCustomComponent && roundEnd.CustomWinnerTitle != null) {
            try {
                customTitleJson = net.minecraft.network.chat.Component.Serializer.toJson(roundEnd.CustomWinnerTitle, level.registryAccess());
            } catch (Throwable ignored) {}
        }
        String customWinnerId = "";
        if (isCustom && roundEnd.CustomWinnerID != null && !roundEnd.CustomWinnerID.isBlank()) {
            customWinnerId = roundEnd.CustomWinnerID;
        }
        int customWinnerColor = (isCustom || isCustomComponent) ? roundEnd.CustomWinnerColor : 0;

        return new GameEndTransitionPayload(winStatus.name(), modeId, customWinnerId, customWinnerColor,
                customTitleJson, environmentReady);
    }

    // ==================== MVP 手持道具（按阵营） ====================

    /**
     * 结算画面播放期间，给 MVP 玩家实体设置主手道具：
     * 杀手 → 刀 + 原地举刀；平民 → 空手；警卫 → 枪；中立 → 撬棍。
     * 结算结束后 SRE {@code resetPlayerAfterGame} 会清空背包，无需额外回收。
     */
    private static void applyMvpItem(ServerLevel level) {
        ServerPlayer mvp = resolveMvp(level);
        if (mvp == null) return;

        SRERole role = null;
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game != null) role = game.getRole(mvp);
        } catch (Throwable ignored) {}
        if (role == null) return;

        ItemStack item;
        boolean raiseKnife = false;
        if (role.isVigilanteTeam()) {
            // 警卫（必须先判：canUseKiller 对警卫也为 true）
            item = new ItemStack(TMMItems.REVOLVER);
        } else if (role.isNeutrals() || role.isNeutralForKiller() || role.isNeutralForInnocent()) {
            // 中立 → 撬棍
            item = new ItemStack(TMMItems.CROWBAR);
        } else if (role.canUseKiller() || role.isKiller()) {
            // 杀手 → 刀 + 举刀
            item = new ItemStack(TMMItems.KNIFE);
            raiseKnife = true;
        } else {
            // 平民 → 空手
            item = ItemStack.EMPTY;
        }

        try {
            mvp.setItemInHand(InteractionHand.MAIN_HAND, item);
            if (raiseKnife) {
                mvp.startUsingItem(InteractionHand.MAIN_HAND);
            }
        } catch (Throwable t) {
            LOGGER.warn("[GameEndTransition] applyMvpItem failed for {}", mvp.getName().getString(), t);
        }
    }

    /** 确定 MVP 玩家：优先亡命徒胜者，其次自定义胜者，最后按 hasWin 标记。 */
    private static ServerPlayer resolveMvp(ServerLevel level) {
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game != null) {
                UUID looseEndWinner = game.getLooseEndWinner();
                if (looseEndWinner != null) {
                    ServerPlayer p = level.getServer().getPlayerList().getPlayer(looseEndWinner);
                    if (p != null) return p;
                }
            }
        } catch (Throwable ignored) {}

        try {
            SREGameRoundEndComponent roundEnd = SREGameRoundEndComponent.KEY.get(level);
            if (roundEnd != null) {
                if (!roundEnd.CustomWinnerPlayers.isEmpty()) {
                    UUID id = roundEnd.CustomWinnerPlayers.get(0);
                    ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
                    if (p != null) return p;
                }
                for (SREGameRoundEndComponent.RoundEndData data : roundEnd.players) {
                    if (data.hasWin()) {
                        ServerPlayer p = level.getServer().getPlayerList().getPlayer(data.player().getId());
                        if (p != null) return p;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
