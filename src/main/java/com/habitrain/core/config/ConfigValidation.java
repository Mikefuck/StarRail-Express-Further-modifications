package com.habitrain.core.config;

import com.google.gson.*;
import java.math.BigDecimal;

/** Shared validation for disk candidates, network candidates and task editor fields. */
public final class ConfigValidation {
    private ConfigValidation() {}
    public static float finiteFloat(String value, String field) {
        float result = Float.parseFloat(value);
        if (!Float.isFinite(result)) throw new IllegalArgumentException(field + "必须是有限数值");
        return result;
    }
    public static int integer(String value, String field) {
        try { return new BigDecimal(value).intValueExact(); }
        catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException(field + "必须是有效整数", e); }
    }
    public static void validateTask(JsonObject task) {
        if (task.has("goldReward")) integer(task.get("goldReward").getAsString(), "金币奖励");
        if (task.has("emotionReward")) finiteFloat(task.get("emotionReward").getAsString(), "情绪奖励");
        if (task.has("refreshWeight")) {
            float weight = finiteFloat(task.get("refreshWeight").getAsString(), "刷新权重");
            if (weight < 0) throw new IllegalArgumentException("刷新权重不能为负数；0 表示禁用该任务");
        }
        if (task.has("outlineWidth")) finiteFloat(task.get("outlineWidth").getAsString(), "轮廓宽度");
        if (task.has("enabledMaps") && !task.get("enabledMaps").isJsonArray()) throw new IllegalArgumentException("enabledMaps 必须是数组");
    }
    public static void validate(JsonObject root) {
        finiteTree(root);
        for (String section : new String[]{"global", "tasks", "gameModes", "minigames", "modeMapVote", "environment", "sceneMotion"})
            object(root, section);
        if (root.has("global")) {
            JsonObject global = root.getAsJsonObject("global");
            if (global.has("sheriffCountDivisor") && integer(global.get("sheriffCountDivisor").getAsString(), "警长除数") <= 0)
                throw new IllegalArgumentException("警长除数必须大于0");
            if (global.has("dlcProbabilityTarget")) finiteFloat(global.get("dlcProbabilityTarget").getAsString(), "DLC概率");
            if (global.has("shaderWhitelist") && !global.get("shaderWhitelist").isJsonArray()) throw new IllegalArgumentException("shaderWhitelist 必须是数组");
        }
        if (root.has("tasks")) for (var entry : root.getAsJsonObject("tasks").entrySet()) {
            if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("任务配置必须是对象: " + entry.getKey());
            validateTask(entry.getValue().getAsJsonObject());
        }
        if (root.has("minigames")) object(root.getAsJsonObject("minigames"), "entries");
        if (root.has("modeMapVote")) {
            object(root.getAsJsonObject("modeMapVote"), "modes"); object(root.getAsJsonObject("modeMapVote"), "maps");
            object(root.getAsJsonObject("modeMapVote"), "mapPlayerCountDraw");
        }
        if (root.has("sceneMotion")) {
            object(root.getAsJsonObject("sceneMotion"), "profiles"); object(root.getAsJsonObject("sceneMotion"), "backgrounds");
            com.habitrain.core.network.ConfigUpdateAccessPolicy.filterConfigJson(com.habitrain.core.network.ConfigUpdateScope.FULL_MOD_MENU, root.toString());
        }
    }
    private static void object(JsonObject parent, String key) {
        if (parent.has(key) && !parent.get(key).isJsonObject()) throw new IllegalArgumentException(key + "必须是对象");
    }
    private static void finiteTree(JsonElement value) {
        if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(e -> finiteTree(e.getValue()));
        else if (value.isJsonArray()) value.getAsJsonArray().forEach(ConfigValidation::finiteTree);
        else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && !Double.isFinite(value.getAsDouble()))
            throw new IllegalArgumentException("配置不能包含 NaN 或 Infinity");
    }
}
