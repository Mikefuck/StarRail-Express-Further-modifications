package com.habitrain.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * per-GameMode 配置作用域。
 * DLC 模组可将任意自定义配置存入此处。
 *
 * <p>这是存储容器，不会自动启停模式或派发配置。读取缺失配置不会把默认值写入仓库；
 * 要保存修改，请使用 ConfigRepository.getOrCreateGameModeConfig 或 setGameModeConfig，
 * 再通过 ConfigManager.markDirty/save 提交。Core 的内置模式没有自动消费这些自定义字段。
 * 返回的 JSON 值均为独立快照，修改它们后必须显式 setSetting。
 */
public class GameModeConfigScope {
    private final String gameModeId;
    private volatile boolean enabled = true;
    private final Map<String, JsonElement> customSettings = new ConcurrentHashMap<>();

    public GameModeConfigScope(String gameModeId) {
        this.gameModeId = gameModeId;
    }

    public String getGameModeId() { return gameModeId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public JsonElement getSetting(String key) {
        JsonElement value = key == null ? null : customSettings.get(key);
        return value == null ? null : value.deepCopy();
    }
    public void setSetting(String key, JsonElement value) {
        if (key == null) return;
        if (value == null) customSettings.remove(key);
        else customSettings.put(key, value.deepCopy());
    }
    public Map<String, JsonElement> getCustomSettings() {
        Map<String, JsonElement> snapshot = new java.util.LinkedHashMap<>();
        customSettings.forEach((key, value) -> snapshot.put(key, value.deepCopy()));
        return Collections.unmodifiableMap(snapshot);
    }

    public GameModeConfigScope copy() {
        GameModeConfigScope copy = new GameModeConfigScope(gameModeId);
        copy.enabled = this.enabled;
        this.customSettings.forEach((k, v) -> copy.customSettings.put(k, v != null ? v.deepCopy() : null));
        return copy;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        if (!customSettings.isEmpty()) {
            JsonObject custom = new JsonObject();
            for (Map.Entry<String, JsonElement> e : customSettings.entrySet()) {
                custom.add(e.getKey(), e.getValue() != null ? e.getValue().deepCopy() : null);
            }
            obj.add("customSettings", custom);
        }
        return obj;
    }

    public static GameModeConfigScope fromJson(String gameModeId, JsonObject json) {
        GameModeConfigScope scope = new GameModeConfigScope(gameModeId);
        if (json == null) return scope;
        if (json.has("enabled")) scope.enabled = json.get("enabled").getAsBoolean();
        if (json.has("customSettings") && json.get("customSettings").isJsonObject()) {
            JsonObject custom = json.getAsJsonObject("customSettings");
            for (Map.Entry<String, JsonElement> e : custom.entrySet()) {
                if (e.getValue() != null) {
                    scope.customSettings.put(e.getKey(), e.getValue().deepCopy());
                }
            }
        }
        return scope;
    }
}
