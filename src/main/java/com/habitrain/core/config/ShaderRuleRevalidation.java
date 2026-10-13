package com.habitrain.core.config;

import com.habitrain.core.network.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Connection reports survive disabled rules; changed rules first request a fresh report. */
public final class ShaderRuleRevalidation {
    private static final Map<UUID, String> REPORTS = new HashMap<>();
    private static final Map<UUID, Long> DEADLINES = new HashMap<>();
    private static final Map<UUID, Long> REQUIRED_REVISIONS = new HashMap<>();
    public static boolean awaitingReport(UUID id) { return DEADLINES.containsKey(id); }
    private ShaderRuleRevalidation() {}
    public static void init() { ServerTickEvents.END_SERVER_TICK.register(server -> {
        long now = System.currentTimeMillis();
        for (var id : new ArrayList<>(DEADLINES.keySet())) if (now >= DEADLINES.get(id)) {
            DEADLINES.remove(id); REQUIRED_REVISIONS.remove(id); var player = server.getPlayerList().getPlayer(id);
            if (player != null && ConfigManager.getInstance().isShaderWhitelistEnabled()) player.connection.disconnect(Component.literal("光影规则已更新，但未收到新的客户端报告，请重新加入"));
        }
    }); }
    public static void report(ServerPlayer player, String shader, long ruleRevision) {
        if (!player.server.isSameThread()) throw new IllegalStateException("光影检查必须在服务器线程");
        Long required = REQUIRED_REVISIONS.get(player.getUUID());
        if (required != null && ruleRevision != required) return;
        REPORTS.put(player.getUUID(), shader == null ? "" : shader); DEADLINES.remove(player.getUUID()); REQUIRED_REVISIONS.remove(player.getUUID()); evaluate(player);
    }
    public static void rulesChanged(MinecraftServer server, long revision) {
        if (server == null) return;
        if (!server.isSameThread()) throw new IllegalStateException("规则重评必须在服务器线程");
        for (var player : server.getPlayerList().getPlayers()) {
            if (!ConfigManager.getInstance().isShaderWhitelistEnabled()) { DEADLINES.remove(player.getUUID()); REQUIRED_REVISIONS.remove(player.getUUID()); continue; }
            if (ServerPlayNetworking.canSend(player, ShaderReportRequestPayload.TYPE)) {
                DEADLINES.put(player.getUUID(), System.currentTimeMillis() + 15_000);
                REQUIRED_REVISIONS.put(player.getUUID(), revision);
                ServerPlayNetworking.send(player, new ShaderReportRequestPayload(revision));
            } else if (REPORTS.containsKey(player.getUUID())) evaluate(player);
            else {
                DEADLINES.put(player.getUUID(), System.currentTimeMillis() + 15_000);
                player.sendSystemMessage(Component.literal("光影规则已更新，请在15秒内重新上报；旧客户端可重新加入以报告当前光影"));
            }
        }
    }
    private static void evaluate(ServerPlayer player) {
        ConfigManager cfg = ConfigManager.getInstance(); String shader = REPORTS.get(player.getUUID());
        if (!cfg.isShaderWhitelistEnabled() || shader == null || shader.isEmpty()) return;
        if (cfg.getShaderWhitelist().stream().noneMatch(name -> name.equalsIgnoreCase(shader)))
            player.connection.disconnect(Component.literal("未授权的光影包：" + shader + "。请关闭光影或使用服务器允许的包后重新加入。"));
    }
    public static void clearPlayer(UUID id) { REPORTS.remove(id); DEADLINES.remove(id); REQUIRED_REVISIONS.remove(id); }
    public static void clear() { REPORTS.clear(); DEADLINES.clear(); REQUIRED_REVISIONS.clear(); }
}
