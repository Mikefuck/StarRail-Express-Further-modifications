package com.habitrain.core.config;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.ArrayList;
import java.util.List;

/**
 * 单个任务的配置项 — 取代 HabiTaskConfigEntry。
 */
public class TaskConfigEntry {
    private static final Logger LOGGER = LoggerFactory.getLogger("TaskConfigEntry");
    public boolean enabled = true;
    public List<String> enabledMaps = new ArrayList<>();
    public int mapFilterMode = 0;

    @Deprecated(forRemoval=true)
    public List<String> disabledMaps = new ArrayList<>();
    public int instinctColor = 0xB4C8C8C8;
    public float outlineWidth = 4.0f;

    public int goldReward = 0;
    public boolean hasGoldReward = false;
    public float emotionReward = 0f;
    public boolean hasEmotionReward = false;
    public float refreshWeight = 0f;
    public boolean hasRefreshWeight = false;

    /** 停电任务商店价格（仅停电专属任务生效；0 表示使用商店目录默认价）。 */
    public int shopPrice = 0;
    public boolean hasShopPrice = false;

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

        json.addProperty("mapFilterMode", mapFilterMode);
        json.addProperty("instinctColor", instinctColor);
        json.addProperty("outlineWidth", outlineWidth);
        if (hasGoldReward) json.addProperty("goldReward", goldReward);
        if (hasEmotionReward) json.addProperty("emotionReward", emotionReward);
        if (hasRefreshWeight) json.addProperty("refreshWeight", refreshWeight);
        if (hasShopPrice) json.addProperty("shopPrice", shopPrice);

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
        }
        // 缺 mapFilterMode 时默认 0（全部允许），不再因 enabledMaps 非空隐式升级为白名单（mode=1）。
        // mode=0 下 enabledMaps 仅作信息记录、不影响启用判断（见 ConfigManager.isMapAllowed）。
        if (json.has("disabledMaps")) {
            var arr = json.getAsJsonArray("disabledMaps");
            if (arr.size() > 0) {
                LOGGER.warn("检测到已废弃的 'disabledMaps' 字段（{}项），当前版本仅读取 enabledMaps + mapFilterMode。"
                        + "如需禁用特定地图，请改用 mapFilterMode=2 + enabledMaps（黑名单语义）。",
                        arr.size());
            }
        }
        if (json.has("instinctColor")) entry.instinctColor = json.get("instinctColor").getAsInt();
        if (json.has("outlineWidth")) entry.outlineWidth = json.get("outlineWidth").getAsFloat();
        if (json.has("goldReward")) {
            entry.hasGoldReward = true;
            entry.goldReward = json.get("goldReward").getAsInt();
        }
        if (json.has("emotionReward")) {
            entry.hasEmotionReward = true;
            entry.emotionReward = json.get("emotionReward").getAsFloat();
        }
        if (json.has("refreshWeight")) {
            entry.hasRefreshWeight = true;
            entry.refreshWeight = json.get("refreshWeight").getAsFloat();
        }
        if (json.has("shopPrice")) {
            entry.hasShopPrice = true;
            entry.shopPrice = json.get("shopPrice").getAsInt();
        }
        return entry;
    }

    public int getColor() {
        return instinctColor;
    }

    public static TaskConfigEntry createDefault() {
        return new TaskConfigEntry(true);
    }
}
