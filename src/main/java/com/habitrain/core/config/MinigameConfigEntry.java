package com.habitrain.core.config;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个小游戏（QuestMinigame）的配置项 — 仿 {@link TaskConfigEntry}。
 *
 * <p>小游戏本身由 SRE 的 {@code QuestMinigames} 注册表提供，HabiTrain 在此基础上
 * 叠加自己的配置层（启用/地图过滤/透视颜色/轮廓/奖励/刷新权重），
 * 存储在 {@code config/habitrain_core.json} 的 {@code "minigames"} 段。</p>
 */
public class MinigameConfigEntry {
    public static final int DEFAULT_INSTINCT_COLOR = 0xB4C8C8C8;

    public boolean enabled = true;
    public List<String> enabledMaps = new ArrayList<>();
    public int mapFilterMode = 0;

    public int instinctColor = DEFAULT_INSTINCT_COLOR;
    public boolean hasInstinctColor = false;
    public float outlineWidth = 4.0f;

    public int goldReward = 0;
    public boolean hasGoldReward = false;
    public float emotionReward = 0f;
    public boolean hasEmotionReward = false;
    public float refreshWeight = 0f;
    public boolean hasRefreshWeight = false;

    public MinigameConfigEntry() {}

    public MinigameConfigEntry(boolean enabled) {
        this.enabled = enabled;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);

        var enabledList = new com.google.gson.JsonArray();
        for (String map : enabledMaps) enabledList.add(map);
        json.add("enabledMaps", enabledList);

        json.addProperty("mapFilterMode", mapFilterMode);
        json.addProperty("hasInstinctColor", hasInstinctColor);
        json.addProperty("instinctColor", instinctColor);
        json.addProperty("outlineWidth", outlineWidth);
        if (hasGoldReward) json.addProperty("goldReward", goldReward);
        if (hasEmotionReward) json.addProperty("emotionReward", emotionReward);
        if (hasRefreshWeight) json.addProperty("refreshWeight", refreshWeight);

        return json;
    }

    public static MinigameConfigEntry fromJson(JsonObject json) {
        ConfigValidation.validateTask(json);
        MinigameConfigEntry entry = new MinigameConfigEntry();
        if (json.has("enabled")) entry.enabled = json.get("enabled").getAsBoolean();
        if (json.has("enabledMaps")) {
            var arr = json.getAsJsonArray("enabledMaps");
            for (var el : arr) entry.enabledMaps.add(el.getAsString());
        }
        if (json.has("mapFilterMode")) {
            entry.mapFilterMode = Math.max(0, Math.min(2, json.get("mapFilterMode").getAsInt()));
        }
        // 缺 mapFilterMode 时默认 0，不再隐式升级为白名单（同 TaskConfigEntry）。
        if (json.has("instinctColor")) entry.instinctColor = json.get("instinctColor").getAsInt();
        if (json.has("hasInstinctColor")) {
            entry.hasInstinctColor = json.get("hasInstinctColor").getAsBoolean();
        } else if (json.has("instinctColor")) {
            entry.hasInstinctColor = entry.instinctColor != DEFAULT_INSTINCT_COLOR;
        }
        if (json.has("outlineWidth")) entry.outlineWidth = json.get("outlineWidth").getAsFloat();
        if (json.has("goldReward")) {
            entry.hasGoldReward = true;
            entry.goldReward = ConfigValidation.integer(json.get("goldReward").getAsString(), "金币奖励");
        }
        if (json.has("emotionReward")) {
            entry.hasEmotionReward = true;
            entry.emotionReward = json.get("emotionReward").getAsFloat();
        }
        if (json.has("refreshWeight")) {
            entry.hasRefreshWeight = true;
            entry.refreshWeight = json.get("refreshWeight").getAsFloat();
        }
        return entry;
    }

    /** 判断该小游戏在指定地图名下是否允许分配（忽略大小写；mode=1 白名单空列表时返回 false）。 */
    public boolean isAllowedOnMap(String mapName) {
        if (mapName == null || mapName.isEmpty()) return true;
        if (mapFilterMode == 0) return true;
        boolean listEmpty = enabledMaps == null || enabledMaps.isEmpty();
        if (mapFilterMode == 1) {
            if (listEmpty) return false;
            for (String m : enabledMaps) if (m != null && m.equalsIgnoreCase(mapName)) return true;
            return false;
        }
        // 2 = 黑名单
        if (listEmpty) return true;
        for (String m : enabledMaps) if (m != null && m.equalsIgnoreCase(mapName)) return false;
        return true;
    }

    public int getColor() {
        return instinctColor;
    }

    public float getOutlineWidth() {
        return outlineWidth;
    }

    public float getRefreshWeight() {
        return hasRefreshWeight ? refreshWeight : 1.0f;
    }

    public MinigameConfigEntry copy() {
        MinigameConfigEntry c = new MinigameConfigEntry(this.enabled);
        if (this.enabledMaps != null) {
            c.enabledMaps = new ArrayList<>(this.enabledMaps);
        }
        c.mapFilterMode = this.mapFilterMode;
        c.instinctColor = this.instinctColor;
        c.hasInstinctColor = this.hasInstinctColor;
        c.outlineWidth = this.outlineWidth;
        c.goldReward = this.goldReward;
        c.hasGoldReward = this.hasGoldReward;
        c.emotionReward = this.emotionReward;
        c.hasEmotionReward = this.hasEmotionReward;
        c.refreshWeight = this.refreshWeight;
        c.hasRefreshWeight = this.hasRefreshWeight;
        return c;
    }

    public static MinigameConfigEntry createDefault() {
        return new MinigameConfigEntry(true);
    }
}
