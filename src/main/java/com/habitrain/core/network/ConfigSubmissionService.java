package com.habitrain.core.network;

import com.google.gson.*;
import com.habitrain.core.config.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** All connected editors, including the integrated host, enter here on the server thread. */
public final class ConfigSubmissionService {
    private record Remembered(String requestJson, ConfigSaveResult result) {}
    private static final Map<UUID, LinkedHashMap<String, Remembered>> RESULTS = new HashMap<>();
    private ConfigSubmissionService() {}
    public static void clearPlayer(UUID player) { RESULTS.remove(player); }
    public static void clear() { RESULTS.clear(); }
    public static void handle(MinecraftServer server, ServerPlayer player, String json) {
        if (player == null) return;
        if (!server.isSameThread()) throw new IllegalStateException("配置提交必须在服务器线程");
        ConfigManager manager = ConfigManager.getInstance(); String requestId = boundedRequestId(json);
        ConfigSaveResult result;
        try {
            if (!player.hasPermissions(2)) throw new IllegalArgumentException("需要 OP2 权限");
            JsonObject request = JsonParser.parseString(json).getAsJsonObject();
            if (!request.has("protocol") || request.get("protocol").getAsInt() != ConfigPatch.PROTOCOL
                    || !ServerPlayNetworking.canSend(player, ConfigResultPayload.TYPE)) throw new IllegalArgumentException("请使用支持配置确认协议的配套客户端版本");
            requestId = request.get("requestId").getAsString(); UUID.fromString(requestId);
            var remembered = RESULTS.computeIfAbsent(player.getUUID(), key -> new LinkedHashMap<>());
            var prior = remembered.get(requestId);
            if (prior != null) {
                result = prior.requestJson.equals(json) ? prior.result : ConfigSaveResult.failed("同一请求编号不能携带不同内容");
                reply(player, manager, requestId, result); return;
            }
            if (!C2SRateLimiter.tryAcquire(player.getUUID(), "config_submit", 250)) {
                reply(player, manager, requestId, ConfigSaveResult.failed("提交过快，请稍后重试")); return;
            }
            ConfigUpdateScope scope = ConfigUpdateScope.fromConfigJson(json);
            if (!ConfigUpdateAccessPolicy.isAllowed(scope, true, server.isDedicatedServer(), MenuGateService.isEnabled(), MenuGateService.isAllowed(player)))
                throw new IllegalArgumentException("完整 Mod 菜单需要服务器后台单独授权");
            String target = "";
            if (scope == ConfigUpdateScope.ADMIN_SCENE_TOOL) {
                var allowed = new LinkedHashSet<>(manager.getModeMapVoteSettings().maps.keySet()); allowed.addAll(manager.getSceneMotionSettings().profiles.keySet());
                target = ConfigUpdateAccessPolicy.resolveAdminSceneTargetMap(ConfigUpdateScope.sceneMapKeyFromConfigJson(json),
                        com.habitrain.core.game.sre.scene.SreSceneContextResolver.INSTANCE.resolve(player.serverLevel()).mapKey(), allowed);
            }
            Set<String> oldAssets = additionalAssets(manager.getSceneMotionSettings());
            String before = manager.toJsonString();
            result = manager.submitPatch(request.getAsJsonObject("patch"), request.get("baseRevision").getAsLong(), scope, target);
            if (result.successful()) {
                if (scope == ConfigUpdateScope.ADMIN_SCENE_TOOL) com.habitrain.core.C2SReceiverRegistrar.syncSceneToolTarget(player, target);
                if (result.status() == ConfigSaveResult.Status.SAVED) {
                    try {
                        oldAssets.removeAll(additionalAssets(manager.getSceneMotionSettings()));
                        for (String key : oldAssets) com.habitrain.core.scene.server.SceneAssetStore.getInstance().deleteAsset(key);
                        var previous = JsonParser.parseString(before).getAsJsonObject(); var current = JsonParser.parseString(manager.toJsonString()).getAsJsonObject();
                        if (!Objects.equals(previous.get("modeMapVote"), current.get("modeMapVote"))) broadcastMapProfiles(server, manager);
                        FullConfigSyncPayload.broadcastToAll(server);
                    } catch (RuntimeException error) {
                        com.habitrain.core.HabiTrainCore.LOGGER.error("配置已保存，后续发布失败", error);
                        result = new ConfigSaveResult(ConfigSaveResult.Status.SAVED, "配置已保存，但发布失败，请查看服务器日志");
                    }
                }
            }
            // Bound memory to eight small requests; never retain full authoritative snapshots.
            remembered.put(requestId, new Remembered(json, result));
            while (remembered.size() > 8) remembered.remove(remembered.keySet().iterator().next());
        } catch (RuntimeException error) { result = ConfigSaveResult.failed("配置更新被拒绝：" + error.getMessage()); }
        reply(player, manager, requestId, result);
    }
    private static void reply(ServerPlayer player, ConfigManager manager, String id, ConfigSaveResult result) {
        if (ServerPlayNetworking.canSend(player, ConfigResultPayload.TYPE)) ServerPlayNetworking.send(player,
                new ConfigResultPayload(id, manager.revision(), result.status(), result.message(), manager.authoritativeSyncJson()));
        else player.sendSystemMessage(Component.literal("§c" + result.message()));
    }
    private static String boundedRequestId(String json) {
        if (json == null) return "";
        var match = java.util.regex.Pattern.compile("\\\"requestId\\\"\\s*:\\s*\\\"([0-9a-fA-F-]{36})\\\"")
                .matcher(json.substring(0, Math.min(json.length(), 1024)));
        return match.find() ? match.group(1) : "";
    }
    private static Set<String> additionalAssets(com.habitrain.core.api.scene.SceneMotionSettings settings) {
        Set<String> keys = new LinkedHashSet<>();
        settings.backgrounds.forEach((map, backgrounds) -> backgrounds.keySet().forEach(id -> keys.add(com.habitrain.core.api.scene.model.SceneBackgroundKey.assetKey(map, id))));
        return keys;
    }
    private static void broadcastMapProfiles(MinecraftServer server, ConfigManager manager) {
        var maps = manager.getModeMapVoteSettings().maps;
        var profiles = com.habitrain.core.vote.MapVoteProfileStore.loadProfiles(server.overworld(), maps.keySet(), maps);
        for (var fragment : MapVoteProfilePayload.fragment(profiles)) for (var player : server.getPlayerList().getPlayers()) ServerPlayNetworking.send(player, fragment);
    }
}
