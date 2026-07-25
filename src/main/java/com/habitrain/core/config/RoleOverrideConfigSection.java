package com.habitrain.core.config;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON 配置中 "roleOverrides" 段的数据模型。
 * 管理全局开关、逐条目启用/停用、冲突解决选择。
 */
public final class RoleOverrideConfigSection {
    private boolean globalEnabled = true;
    private final Map<String, Boolean> entries = new LinkedHashMap<>();
    private final Map<String, String> conflictResolution = new HashMap<>();

    public static RoleOverrideConfigSection createDefault() {
        return new RoleOverrideConfigSection();
    }

    public static RoleOverrideConfigSection fromJson(JsonObject obj) {
        RoleOverrideConfigSection s = new RoleOverrideConfigSection();
        if (obj.has("globalEnabled")) {
            s.globalEnabled = obj.get("globalEnabled").getAsBoolean();
        }
        if (obj.has("entries") && obj.get("entries").isJsonObject()) {
            JsonObject entriesObj = obj.getAsJsonObject("entries");
            for (var e : entriesObj.entrySet()) {
                s.entries.put(e.getKey(), e.getValue().getAsBoolean());
            }
        }
        if (obj.has("conflictResolution") && obj.get("conflictResolution").isJsonObject()) {
            JsonObject cr = obj.getAsJsonObject("conflictResolution");
            for (var e : cr.entrySet()) {
                s.conflictResolution.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return s;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("globalEnabled", globalEnabled);
        JsonObject entriesObj = new JsonObject();
        for (var e : entries.entrySet()) {
            entriesObj.addProperty(e.getKey(), e.getValue());
        }
        obj.add("entries", entriesObj);
        JsonObject cr = new JsonObject();
        for (var e : conflictResolution.entrySet()) {
            cr.addProperty(e.getKey(), e.getValue());
        }
        obj.add("conflictResolution", cr);
        return obj;
    }

    public boolean isGlobalEnabled() { return globalEnabled; }
    public void setGlobalEnabled(boolean v) { this.globalEnabled = v; }
    public Map<String, Boolean> getEntries() { return entries; }
    public boolean isEnabled(String entryId) { return entries.getOrDefault(entryId, true); }
    public void setEnabled(String entryId, boolean enabled) { entries.put(entryId, enabled); }
    public Map<String, String> getConflictResolution() { return conflictResolution; }
    public void setConflictResolution(String targetId, String entryId) { conflictResolution.put(targetId, entryId); }
}
