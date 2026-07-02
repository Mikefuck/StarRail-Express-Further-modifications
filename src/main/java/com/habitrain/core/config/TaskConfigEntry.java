package com.habitrain.core.config;

import com.google.gson.JsonObject;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;


import java.util.ArrayList;
import java.util.List;

/**
 * 单个任务的配置项 — 取代 HabiTaskConfigEntry。
 */
public class TaskConfigEntry {
    public boolean enabled = true;
    public List<String> enabledMaps = new ArrayList<>();
    public int mapFilterMode = 0;

    @Deprecated
    public List<String> disabledMaps = new ArrayList<>();
    public int instinctColor = 0xB4C8C8C8;
    public float outlineWidth = 4.0f;

    public int goldReward = -1;
    public float emotionReward = -1f;
    public float refreshWeight = -1f;

    public TaskConfigEntry() {}

    public TaskConfigEntry(boolean enabled) {
        this.enabled = enabled;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);

        var enabledList = new com.google.gson.JsonArray();
        for (String map : enabledMaps) enabledList.add(map);
        json.add("enabledMaps", enabledList);

        if (mapFilterMode != 0) json.addProperty("mapFilterMode", mapFilterMode);
        json.addProperty("instinctColor", instinctColor);
        json.addProperty("outlineWidth", outlineWidth);
        if (goldReward >= 0) json.addProperty("goldReward", goldReward);
        if (emotionReward >= 0f) json.addProperty("emotionReward", emotionReward);
        if (refreshWeight >= 0f) json.addProperty("refreshWeight", refreshWeight);

        return json;
    }

    public static TaskConfigEntry fromJson(JsonObject json) {
        TaskConfigEntry entry = new TaskConfigEntry();
        if (json.has("enabled")) entry.enabled = json.get("enabled").getAsBoolean();
        if (json.has("enabledMaps")) {
            var arr = json.getAsJsonArray("enabledMaps");
            for (var el : arr) entry.enabledMaps.add(el.getAsString());
        }
        if (json.has("mapFilterMode")) {
            entry.mapFilterMode = Math.max(0, Math.min(2, json.get("mapFilterMode").getAsInt()));
        } else if (!entry.enabledMaps.isEmpty()) {
            entry.mapFilterMode = 1;
        }
        if (json.has("disabledMaps")) {
            var arr = json.getAsJsonArray("disabledMaps");
            for (var el : arr) entry.disabledMaps.add(el.getAsString());
        }
        if (json.has("instinctColor")) entry.instinctColor = json.get("instinctColor").getAsInt();
        if (json.has("outlineWidth")) entry.outlineWidth = json.get("outlineWidth").getAsFloat();
        if (json.has("goldReward")) entry.goldReward = json.get("goldReward").getAsInt();
        if (json.has("emotionReward")) entry.emotionReward = json.get("emotionReward").getAsFloat();
        if (json.has("refreshWeight")) entry.refreshWeight = json.get("refreshWeight").getAsFloat();
        return entry;
    }

    public int getEffectiveGoldReward(TaskDefinition def) {
        return goldReward >= 0 ? goldReward : -1;
    }

    public float getEffectiveEmotionReward(TaskDefinition def) {
        return emotionReward >= 0f ? emotionReward : -1f;
    }

    public float getEffectiveRefreshWeight(TaskDefinition def) {
        return refreshWeight >= 0f ? refreshWeight : def.getWeight();
    }

    public int getColor() {
        return instinctColor;
    }

    public static TaskConfigEntry createDefault() {
        return new TaskConfigEntry(true);
    }
}
