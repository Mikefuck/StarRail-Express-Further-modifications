package com.habitrain.core.config;

import com.habitrain.core.network.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/** Package-name compatibility check based on client reports; not a trusted anti-cheat attestation. */
public final class ShaderRuleRevalidation {
    private static final ShaderReportTracker TRACKER = new ShaderReportTracker();
    private ShaderRuleRevalidation() {}
    /** Pure query: rate limits are applied once by the receiver, including wrong revisions. */
    public static boolean awaitingReport(UUID id) { return id != null && TRACKER.awaiting(id); }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = System.nanoTime();
            boolean enabled = ConfigManager.getInstance().isShaderWhitelistEnabled();
            if (enabled) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    UUID id = player.getUUID();
                    if (!TRACKER.hasReport(id) && !TRACKER.awaiting(id)) TRACKER.begin(id, -1, now);
                }
            }
            for (UUID id : TRACKER.expire(now)) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null && enabled)
                    player.connection.disconnect(Component.literal("光影规则已更新或未收到客户端光影报告，请重新加入"));
            }
        });
    }

    public static void onPlayerJoin(ServerPlayer player) {
        if (player == null) return;
        UUID id = player.getUUID();
        TRACKER.clear(id);
        if (ConfigManager.getInstance().isShaderWhitelistEnabled()) TRACKER.begin(id, -1, System.nanoTime());
    }

    public static void report(ServerPlayer player, String shader, long ruleRevision) {
        if (player == null) return;
        if (!player.server.isSameThread()) throw new IllegalStateException("光影检查必须在服务器线程");
        if (!ShaderReportTracker.validName(shader)) {
            player.connection.disconnect(Component.literal("光影上报格式无效，请重新加入"));
            return;
        }
        if (TRACKER.accept(player.getUUID(), shader, ruleRevision)) evaluate(player);
    }

    public static void rulesChanged(MinecraftServer server, long revision) {
        if (server == null) return;
        if (!server.isSameThread()) throw new IllegalStateException("规则重评必须在服务器线程");
        long now = System.nanoTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (!ConfigManager.getInstance().isShaderWhitelistEnabled()) {
                TRACKER.cancelPending(id);
            } else if (ServerPlayNetworking.canSend(player, ShaderReportRequestPayload.TYPE)) {
                TRACKER.begin(id, revision, now);
                ServerPlayNetworking.send(player, new ShaderReportRequestPayload(revision));
            } else if (TRACKER.hasReport(id)) {
                evaluate(player);
            } else {
                TRACKER.begin(id, -1, now);
                player.sendSystemMessage(Component.literal("光影规则已更新，请在15秒内重新上报；旧客户端可重新加入以报告当前光影"));
            }
        }
    }

    private static void evaluate(ServerPlayer player) {
        ConfigManager cfg = ConfigManager.getInstance();
        if (!cfg.isShaderWhitelistEnabled()) return;
        String shader = TRACKER.report(player.getUUID());
        if (shader == null) {
            if (!TRACKER.awaiting(player.getUUID())) TRACKER.begin(player.getUUID(), -1, System.nanoTime());
            return;
        }
        if (shader.isEmpty()) return; // disabled shaders remain supported
        String trimmed = shader.trim();
        if (cfg.getShaderWhitelist().stream().noneMatch(name -> name != null && name.trim().equalsIgnoreCase(trimmed)))
            player.connection.disconnect(Component.literal("未授权的光影包：" + trimmed + "。请关闭光影或使用服务器允许的包后重新加入。"));
    }
    public static void clearPlayer(UUID id) { if (id != null) TRACKER.clear(id); }
    public static void clear() { TRACKER.clear(); }
}
