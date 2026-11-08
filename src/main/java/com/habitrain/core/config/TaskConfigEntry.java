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
    /** Default gray used when a task has no Mod Menu color override. */
    public static final int DEFAULT_INSTINCT_COLOR = 0xB4C8C8C8;

    public boolean enabled = true;
    public List<String> enabledMaps = new ArrayList<>();
    public int mapFilterMode = 0;

    public int instinctColor = DEFAULT_INSTINCT_COLOR;
    /**
     * True when the operator explicitly set a color in Mod Menu.
     * False means {@link com.habitrain.core.api.TaskDefinition#getInstinctColorRGB()} wins.
     */
    public boolean hasInstinctColor = false;
    /**
     * True when {@code instinctColor} was loaded from a JSON file that predates
     * {@link #hasInstinctColor}. Legacy default-gray values are treated as unset
     * at resolve time if the task definition is not gray.
     */
    public boolean instinctColorFromLegacyJson = false;
    public float outlineWidth = 4.0f;

    public int goldReward = 0;
    public boolean hasGoldReward = false;
    public float emotionReward = 0f;
    public boolean hasEmotionReward = false;
    public float refreshWeight = 0f;
    public boolean hasRefreshWeight = false;

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
        json.addProperty("hasInstinctColor", hasInstinctColor);
        json.addProperty("instinctColor", instinctColor);
        json.addProperty("outlineWidth", outlineWidth);
        if (hasGoldReward) json.addProperty("goldReward", goldReward);
        if (hasEmotionReward) json.addProperty("emotionReward", emotionReward);
        if (hasRefreshWeight) json.addProperty("refreshWeight", refreshWeight);

        return json;
    }

    public static TaskConfigEntry fromJson(JsonObject json) {
        ConfigValidation.validateTask(json);
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
        if (json.has("hasInstinctColor")) {
            entry.hasInstinctColor = json.get("hasInstinctColor").getAsBoolean();
            entry.instinctColorFromLegacyJson = false;
        } else if (json.has("instinctColor")) {
            entry.hasInstinctColor = true;
            entry.instinctColorFromLegacyJson = true;
        } else {
            entry.hasInstinctColor = false;
            entry.instinctColorFromLegacyJson = false;
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

    public int getColor() {
        return instinctColor;
    }

    /** 判断该任务在指定地图名下是否允许分配（忽略大小写；mode=1 白名单空列表时返回 false）。 */
    public boolean isAllowedOnMap(String mapName) {
        if (mapName == null || mapName.isEmpty()) return true;
        if (mapFilterMode == 0) return true;
        boolean listEmpty = enabledMaps == null || enabledMaps.isEmpty();
        if (mapFilterMode == 1) {
            if (listEmpty) return false;
            for (String m : enabledMaps) {
                if (m != null && m.equalsIgnoreCase(mapName)) return true;
            }
            return false;
        }
        // 2 = 黑名单
        if (listEmpty) return true;
        for (String m : enabledMaps) {
            if (m != null && m.equalsIgnoreCase(mapName)) return false;
        }
        return true;
    }

    public TaskConfigEntry copy() {
        TaskConfigEntry c = new TaskConfigEntry(this.enabled);
        if (this.enabledMaps != null) {
            c.enabledMaps = new ArrayList<>(this.enabledMaps);
        }
        c.mapFilterMode = this.mapFilterMode;
        c.instinctColor = this.instinctColor;
        c.hasInstinctColor = this.hasInstinctColor;
        c.instinctColorFromLegacyJson = this.instinctColorFromLegacyJson;
        c.outlineWidth = this.outlineWidth;
        c.goldReward = this.goldReward;
        c.hasGoldReward = this.hasGoldReward;
        c.emotionReward = this.emotionReward;
        c.hasEmotionReward = this.hasEmotionReward;
        c.refreshWeight = this.refreshWeight;
        c.hasRefreshWeight = this.hasRefreshWeight;
        return c;
    }

    public static TaskConfigEntry createDefault() {
        return new TaskConfigEntry(true);
    }
}
