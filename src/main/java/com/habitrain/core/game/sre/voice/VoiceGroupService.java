package com.habitrain.core.game.sre.voice;

import com.habitrain.core.game.sre.mode.SREGameModeBase;
import com.habitrain.core.game.sre.mode.SREGameModeState;
import com.habitrain.core.game.sre.rest.EliminatedRestAreaService;
import com.mojang.brigadier.context.CommandContext;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import io.wifi.starrailexpress.compat.TrainVoicePlugin;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 语音群组服务 — 处理 /instantgroup 命令的临时语音群组创建、玩家加入逻辑、大厅群组操作及淘汰观战群组隔离保障。
 * 所有直接触碰 {@code de.maxhenkel.voicechat.api.*} 的调用均收口在本类中，调用方先经过 {@link VoiceChatPresence#isLoaded()} 守卫，
 * 避免未安装 Simple Voice Chat 时触发 {@link NoClassDefFoundError}。
 */
public final class VoiceGroupService {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|VoiceGroupService");

    private VoiceGroupService() {}

    public static boolean isServerApiReady() {
        if (!VoiceChatPresence.isLoaded()) {
            return false;
        }
        try {
            return !TrainVoicePlugin.isVoiceChatMissing() && TrainVoicePlugin.SERVER_API != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean tryAddPlayerToLobbyGroup(SREGameModeState state, UUID lobbyGroupId, UUID playerUUID) {
        if (!isServerApiReady()) {
            return false;
        }
        VoicechatServerApi api = TrainVoicePlugin.SERVER_API;
        VoicechatConnection connection = api.getConnectionOf(playerUUID);
        if (connection == null) return false;

        try {
            Group current = connection.getGroup();
            if (current != null && lobbyGroupId.equals(current.getId())) {
                return true;
            }

            Group lobbyGroup = state.getLobbyGroup() instanceof Group existing ? existing : null;
            if (lobbyGroup == null) {
                lobbyGroup = api.groupBuilder()
                        .setId(lobbyGroupId)
                        .setName("LobbyChat")
                        .setPersistent(true)
                        .setType(Group.Type.OPEN)
                        .setHidden(false)
                        .build();
                state.setLobbyGroup(lobbyGroup);
            }
            connection.setGroup(lobbyGroup);
            LOGGER.info("[VoiceGroup] successfully added {} to lobby group", playerUUID);
            return true;
        } catch (Exception e) {
            LOGGER.error("[VoiceGroup] failed to set group for player {}", playerUUID, e);
            return false;
        }
    }

    public static int leaveLobbyGroupForAllOnline(MinecraftServer server, UUID lobbyGroupId) {
        if (server == null || !isServerApiReady()) {
            return 0;
        }
        VoicechatServerApi api = TrainVoicePlugin.SERVER_API;
        int left = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                VoicechatConnection connection = api.getConnectionOf(player.getUUID());
                if (connection == null) continue;
                Group group = connection.getGroup();
                if (group != null && lobbyGroupId.equals(group.getId())) {
                    connection.setGroup(null);
                    left++;
                }
            } catch (Exception e) {
                LOGGER.debug("[VoiceGroup] failed to leave lobby group for {}", player.getUUID(), e);
            }
        }
        return left;
    }

    public static boolean isPlayerInLobbyGroup(UUID playerId, UUID lobbyGroupId) {
        if (playerId == null || !isServerApiReady()) {
            return false;
        }
        try {
            VoicechatConnection connection = TrainVoicePlugin.SERVER_API.getConnectionOf(playerId);
            if (connection == null) {
                return false;
            }
            Group group = connection.getGroup();
            return group != null && lobbyGroupId.equals(group.getId());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 确保已淘汰/休息区玩家处于 {@link Group.Type#ISOLATED} 的 {@code Train Spectators} 语音群组中，
     * 防止死人与活人近距离语音互通，并在重连后待 SVC UDP 握手完成时自动补入群组。
     */
    public static void ensureSpectatorVoiceGroup(UUID playerId) {
        if (playerId == null || !VoiceChatPresence.isLoaded()) {
            return;
        }
        try {
            if (TrainVoicePlugin.isVoiceChatMissing() || TrainVoicePlugin.SERVER_API == null) {
                return;
            }
            VoicechatServerApi api = TrainVoicePlugin.SERVER_API;
            VoicechatConnection conn = api.getConnectionOf(playerId);
            if (conn == null) {
                return;
            }
            if (TrainVoicePlugin.GROUP == null || TrainVoicePlugin.GROUP.getType() != Group.Type.ISOLATED) {
                TrainVoicePlugin.GROUP = api.groupBuilder()
                        .setHidden(true)
                        .setId(TrainVoicePlugin.GROUP_ID)
                        .setName("Train Spectators")
                        .setPersistent(true)
                        .setType(Group.Type.ISOLATED)
                        .build();
            }
            Group current = conn.getGroup();
            if (TrainVoicePlugin.GROUP != null && (current == null
                    || !TrainVoicePlugin.GROUP_ID.equals(current.getId())
                    || current.getType() != Group.Type.ISOLATED)) {
                conn.setGroup(TrainVoicePlugin.GROUP);
            }
        } catch (Throwable t) {
            LOGGER.debug("[VoiceGroupService] ensureSpectatorVoiceGroup failed for {}: {}", playerId, t.toString());
        }
    }

    /**
     * 安全移出观战语音群组（仅在安装了 voicechat 时执行）。
     */
    public static void resetPlayerVoiceGroup(UUID playerId) {
        if (playerId == null || !VoiceChatPresence.isLoaded()) {
            return;
        }
        try {
            TrainVoicePlugin.resetPlayer(playerId);
        } catch (Throwable t) {
            LOGGER.debug("[VoiceGroupService] resetPlayerVoiceGroup failed for {}: {}", playerId, t.toString());
        }
    }

    /**
     * 执行 /instantgroup 命令：将范围内玩家加入临时语音群组。
     */
    public static int executeInstantGroup(CommandContext<CommandSourceStack> context, int range) {
        // 审核 B25：显式的可选依赖守卫（voicechat 只在 fabric.mod.json 里 suggests）。
        if (!VoiceChatPresence.isLoaded()) {
            context.getSource().sendFailure(Component.translatable("message.habitrain_core.voice.unavailable"));
            return 0;
        }
        CommandSourceStack source = context.getSource();
        ServerPlayer sender = source.getPlayer();
        if (sender == null) {
            source.sendFailure(Component.translatable("message.habitrain_core.voice.players_only"));
            return 0;
        }
        MinecraftServer srv = source.getServer();
        if ((srv != null && SREGameModeBase.isAnySreGameStartingOrRunning(srv)) || GameUtils.isGameRunning(sender)) {
            source.sendFailure(Component.translatable("message.habitrain_core.voice.match_running"));
            return 0;
        }
        if (TrainVoicePlugin.isVoiceChatMissing() || TrainVoicePlugin.SERVER_API == null) {
            source.sendFailure(Component.translatable("message.habitrain_core.voice.not_ready"));
            return 0;
        }
        VoicechatServerApi api = TrainVoicePlugin.SERVER_API;
        if (api.getConnectionOf(sender.getUUID()) == null) {
            source.sendFailure(Component.translatable("message.habitrain_core.voice.not_connected"));
            return 0;
        }
        boolean senderEliminated = GameUtils.isPlayerEliminated(sender);
        boolean senderResting = EliminatedRestAreaService.isResting(sender);
        List<ServerPlayer> nearby = new ArrayList<>();
        if (srv != null) {
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                if (p.getUUID().equals(sender.getUUID())) continue;
                if (!p.level().dimension().equals(sender.level().dimension())) continue;
                if (GameUtils.isPlayerEliminated(p) != senderEliminated) continue;
                if (EliminatedRestAreaService.isResting(p) != senderResting) continue;
                if (p.distanceToSqr(sender) <= (double) range * range) {
                    nearby.add(p);
                }
            }
        }
        if (nearby.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("message.habitrain_core.voice.no_nearby", range), true);
            return 0;
        }
        Group tempGroup;
        try {
            tempGroup = api.groupBuilder()
                    .setId(UUID.randomUUID()).setName("临时群组")
                    .setPersistent(false).setType(Group.Type.OPEN).setHidden(false).build();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("message.habitrain_core.voice.create_failed"));
            return 0;
        }
        try {
            VoicechatConnection conn = api.getConnectionOf(sender.getUUID());
            if (conn != null) conn.setGroup(tempGroup);
        } catch (Exception e) {
            LOGGER.warn("将发送者加入临时语音群组时失败", e);
        }
        int count = 0;
        for (ServerPlayer p : nearby) {
            try {
                VoicechatConnection conn = api.getConnectionOf(p.getUUID());
                if (conn != null) {
                    conn.setGroup(tempGroup);
                    count++;
                }
            } catch (Exception e) {
                LOGGER.warn("将附近玩家加入临时语音群组时失败", e);
            }
        }
        final int finalCount = count;
        source.sendSuccess(() -> Component.translatable("message.habitrain_core.voice.nearby_added", finalCount, range), true);
        Component notify = Component.translatable("message.habitrain_core.voice.joined");
        for (ServerPlayer p : nearby) {
            if (api.getConnectionOf(p.getUUID()) != null) p.sendSystemMessage(notify);
        }
        LOGGER.info("[InstantGroup] {} 执行 /instantgroup {}，{} 名玩家", sender.getName().getString(), range, count);
        return count;
    }
}
