package com.habitrain.core.client.config;

import com.google.gson.*;
import com.habitrain.core.config.*;
import com.habitrain.core.network.*;
import com.habitrain.core.client.gui.menu.ConfigUpdateContext;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Environment(EnvType.CLIENT)
public final class ClientConfigSubmission {
    private static String pendingId;
    private static String submittedJson;
    private static long startedAt;
    private ClientConfigSubmission() {}
    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(ConfigResultPayload.TYPE, (result, context) -> context.client().execute(() -> accept(result)));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingId != null && System.currentTimeMillis() - startedAt >= 15_000) {
                clear(); show(ConfigSaveResult.failed("服务器未确认保存，草稿已保留，请稍后重试"));
            }
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }
    public static ConfigSaveResult submit(String baseJson, String draftJson, long baseRevision) {
        if (pendingId != null) return new ConfigSaveResult(ConfigSaveResult.Status.SUBMITTING, "正在等待服务器确认");
        if (Minecraft.getInstance().getConnection() == null) return ConfigSaveResult.failed("连接已断开，草稿未提交");
        if (baseRevision < 0 || !ClientPlayNetworking.canSend(ConfigUpdatePayload.TYPE)) return ConfigSaveResult.failed("服务器不支持配置确认协议，请使用配套版本");
        try {
            var scope = ConfigUpdateContext.currentScope(); String sceneMap = ConfigUpdateContext.currentSceneMapKey();
            JsonObject patch = ConfigPatch.filtered(ConfigPatch.diff(JsonParser.parseString(baseJson).getAsJsonObject(), JsonParser.parseString(draftJson).getAsJsonObject()), scope, sceneMap);
            if (ConfigPatch.empty(patch) && scope != ConfigUpdateScope.ADMIN_SCENE_TOOL) return ConfigSaveResult.unchanged();
            JsonObject request = new JsonObject(); String id = UUID.randomUUID().toString();
            request.addProperty("protocol", ConfigPatch.PROTOCOL); request.addProperty("requestId", id); request.addProperty("baseRevision", baseRevision); request.add("patch", patch);
            String wire = ConfigUpdateScope.attachToConfigJson(request.toString(), scope, sceneMap);
            if (wire.getBytes(StandardCharsets.UTF_8).length > ConfigPatch.MAX_BYTES) return ConfigSaveResult.failed("本次修改超过网络大小限制，请分次保存");
            pendingId = id; submittedJson = draftJson; startedAt = System.currentTimeMillis();
            try { ClientPlayNetworking.send(new ConfigUpdatePayload(wire)); }
            catch (RuntimeException error) { clear(); return ConfigSaveResult.failed("配置提交失败：" + error.getMessage()); }
            return new ConfigSaveResult(ConfigSaveResult.Status.SUBMITTING, "正在提交服务器配置");
        } catch (RuntimeException error) { return ConfigSaveResult.failed("配置输入无效：" + error.getMessage()); }
    }
    private static void accept(ConfigResultPayload result) {
        if (!java.util.Objects.equals(pendingId, result.requestId())) return;
        String submitted = submittedJson; clear();
        boolean success = result.status() == ConfigSaveResult.Status.SAVED || result.status() == ConfigSaveResult.Status.NO_CHANGE;
        ConfigManager.getInstance().acknowledgeClient(result.authoritativeJson(), result.revision(), success, submitted);
        if (result.status() == ConfigSaveResult.Status.CONFLICT) ConfigManager.getInstance().markClientConfigConflict();
        show(new ConfigSaveResult(result.status(), result.message()));
    }
    public static void clear() { pendingId = null; submittedJson = null; startedAt = 0; }
    public static void show(ConfigSaveResult result) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal((result.successful() ? "§a" : result.status() == ConfigSaveResult.Status.SUBMITTING ? "§e" : "§c") + result.message()), true);
    }
}
