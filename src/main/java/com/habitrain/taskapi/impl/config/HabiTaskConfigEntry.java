package com.habitrain.taskapi.impl.config;

import com.google.gson.JsonObject;
import com.habitrain.taskapi.api.HabiTaskDefinition;
import com.habitrain.taskapi.api.HabiTaskRegistry;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个任务的配置项
 * 存储每个任务的各种可配置属性，包括启用状态、地图过滤、透视颜色、奖励等
 */
public class HabiTaskConfigEntry {
    public boolean enabled = true;
    public List<String> enabledMaps = new ArrayList<>();

    /** 地图过滤模式: 0=不启用(全部地图), 1=白名单(仅列表中的地图), 2=黑名单(排除列表中的地图) */
    public int mapFilterMode = 0;

    @Deprecated
    public List<String> disabledMaps = new ArrayList<>();
    public int instinctColor = new Color(200, 200, 200, 180).getRGB();
    /** 描边粗细（SRE默认 4.0，越大线越粗越明显） */
    public float outlineWidth = 4.0f;

    // ====== 奖励和概率设置 (-1 / -1f 表示使用系统默认值) ======
    /** 金币奖励（-1 = 使用SRE默认值） */
    public int goldReward = -1;
    /** 情绪奖励（-1 = 使用SRE默认值，通常为0.1） */
    public float emotionReward = -1f;
    /** 刷新权重（-1 = 使用任务定义的默认权重） */
    public float refreshWeight = -1f;

    public HabiTaskConfigEntry() {}

    public HabiTaskConfigEntry(boolean enabled) {
        this.enabled = enabled;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);

        var enabledList = new com.google.gson.JsonArray();
        for (String map : enabledMaps) enabledList.add(map);
        json.add("enabledMaps", enabledList);

        // 仅在非默认值时写入
        if (mapFilterMode != 0) json.addProperty("mapFilterMode", mapFilterMode);

        json.addProperty("instinctColor", instinctColor);
        json.addProperty("outlineWidth", outlineWidth);

        // 仅在非默认值时写入，保持配置文件简洁
        if (goldReward >= 0) json.addProperty("goldReward", goldReward);
        if (emotionReward >= 0f) json.addProperty("emotionReward", emotionReward);
        if (refreshWeight >= 0f) json.addProperty("refreshWeight", refreshWeight);

        return json;
    }

    public static HabiTaskConfigEntry fromJson(JsonObject json) {
        HabiTaskConfigEntry entry = new HabiTaskConfigEntry();
        if (json.has("enabled")) {
            entry.enabled = json.get("enabled").getAsBoolean();
        }
        if (json.has("enabledMaps")) {
            var arr = json.getAsJsonArray("enabledMaps");
            for (var el : arr) entry.enabledMaps.add(el.getAsString());
        }
        // ★ 读取地图过滤模式（默认0=不启用筛选）
        if (json.has("mapFilterMode")) {
            entry.mapFilterMode = Math.max(0, Math.min(2, json.get("mapFilterMode").getAsInt()));
        } else if (!entry.enabledMaps.isEmpty()) {
            // 向后兼容：旧配置有地图列表 => 白名单模式
            entry.mapFilterMode = 1;
        }
        if (json.has("disabledMaps")) {
            var arr = json.getAsJsonArray("disabledMaps");
            for (var el : arr) entry.disabledMaps.add(el.getAsString());
        }
        if (json.has("instinctColor")) {
            entry.instinctColor = json.get("instinctColor").getAsInt();
        }
        if (json.has("outlineWidth")) {
            entry.outlineWidth = json.get("outlineWidth").getAsFloat();
        }
        if (json.has("goldReward")) {
            entry.goldReward = json.get("goldReward").getAsInt();
        }
        if (json.has("emotionReward")) {
            entry.emotionReward = json.get("emotionReward").getAsFloat();
        }
        if (json.has("refreshWeight")) {
            entry.refreshWeight = json.get("refreshWeight").getAsFloat();
        }
        return entry;
    }

    /**
     * 获取实际的黄金奖励值
     * @param def 任务定义（用于获取默认值）
     * @return -1 表示使用SRE系统默认值
     */
    public int getEffectiveGoldReward(HabiTaskDefinition def) {
        return goldReward >= 0 ? goldReward : -1;
    }

    /**
     * 获取实际的情绪奖励值
     * @param def 任务定义（用于获取默认值）
     * @return -1 表示使用SRE系统默认值（通常0.1）
     */
    public float getEffectiveEmotionReward(HabiTaskDefinition def) {
        return emotionReward >= 0f ? emotionReward : -1f;
    }

    /**
     * 获取实际的刷新权重
     * @param def 任务定义（用于获取默认值）
     * @return 最终权重值
     */
    public float getEffectiveRefreshWeight(HabiTaskDefinition def) {
        return refreshWeight >= 0f ? refreshWeight : def.getWeight();
    }

    /**
     * 获取Java AWT Color
     */
    public Color getColor() {
        return new Color(instinctColor, true);
    }

    /**
     * 创建具有默认奖励设置的配置（使用任务定义的值）
     */
    public static HabiTaskConfigEntry createDefault() {
        return new HabiTaskConfigEntry(true);
    }
}
