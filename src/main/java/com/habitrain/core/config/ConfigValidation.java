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
    public static void validateStructure(JsonObject root) {
        if (root == null) throw new IllegalArgumentException("配置根节点不能为空");
        finiteTree(root, 0);
        for (String section : new String[]{"global", "tasks", "gameModes", "minigames", "modeMapVote", "environment", "sceneMotion"})
            object(root, section);
        if (root.has("global")) {
            JsonObject global = root.getAsJsonObject("global");
            for (String key : new String[]{"sheriffCountDivisor", "dlcProbabilityTarget", "dlcWeightBoost", "blackoutGlobalCooldownSeconds"}) {
                if (global.has(key) && (!global.get(key).isJsonPrimitive()
                        || !global.getAsJsonPrimitive(key).isNumber()))
                    throw new IllegalArgumentException(key + "必须是数值");
            }
            for (String key : new String[]{"shaderWhitelistEnabled", "knifeDurabilityEnabled",
                    "lobbyVoiceGroupEnabled", "blackoutEffectEnhancementEnabled"}) {
                if (global.has(key) && (!global.get(key).isJsonPrimitive()
                        || !global.getAsJsonPrimitive(key).isBoolean()))
                    throw new IllegalArgumentException(key + "必须是布尔值");
            }
            if (global.has("shaderWhitelist") && !global.get("shaderWhitelist").isJsonArray())
                throw new IllegalArgumentException("shaderWhitelist 必须是数组");
            if (global.has("shaderWhitelist")) for (var shader : global.getAsJsonArray("shaderWhitelist")) {
                if (!shader.isJsonPrimitive() || !shader.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("shaderWhitelist 条目必须是字符串");
            }
        }
        if (root.has("minigames")) {
            JsonObject minigames = root.getAsJsonObject("minigames");
            object(minigames, "entries");
            if (minigames.has("globalEnabled") && (!minigames.get("globalEnabled").isJsonPrimitive()
                    || !minigames.getAsJsonPrimitive("globalEnabled").isBoolean()))
                throw new IllegalArgumentException("minigames.globalEnabled 必须是布尔值");
        }
        if (root.has("modeMapVote")) {
            JsonObject mmv = root.getAsJsonObject("modeMapVote");
            object(mmv, "modes"); object(mmv, "maps"); object(mmv, "mapPlayerCountDraw");
        }
        if (root.has("sceneMotion")) {
            JsonObject sm = root.getAsJsonObject("sceneMotion");
            object(sm, "profiles"); object(sm, "backgrounds");
        }
    }
    public static void validate(JsonObject root) {
        validateStructure(root);
        if (root.has("global")) {
            JsonObject global = root.getAsJsonObject("global");
            if (global.has("sheriffCountDivisor") && integer(global.get("sheriffCountDivisor").getAsString(), "警长除数") <= 0)
                throw new IllegalArgumentException("警长除数必须大于0");
            if (global.has("dlcProbabilityTarget")) {
                float dlcProb = finiteFloat(global.get("dlcProbabilityTarget").getAsString(), "DLC概率");
                if (dlcProb < 0.1f || dlcProb > 0.8f)
                    throw new IllegalArgumentException("DLC概率必须在 0.1 到 0.8 之间");
            }
            if (global.has("shaderWhitelist") && !global.get("shaderWhitelist").isJsonArray()) throw new IllegalArgumentException("shaderWhitelist 必须是数组");
        }
        if (root.has("tasks")) for (var entry : root.getAsJsonObject("tasks").entrySet()) {
            if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("任务配置必须是对象: " + entry.getKey());
            validateTask(entry.getValue().getAsJsonObject());
        }
        if (root.has("minigames")) {
            JsonObject mg = root.getAsJsonObject("minigames");
            object(mg, "entries");
            if (mg.has("entries")) {
                for (var entry : mg.getAsJsonObject("entries").entrySet()) {
                    if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("小游戏配置必须是对象: " + entry.getKey());
                    validateTask(entry.getValue().getAsJsonObject());
                }
            }
        }
        if (root.has("modeMapVote")) {
            JsonObject mmv = root.getAsJsonObject("modeMapVote");
            object(mmv, "modes"); object(mmv, "maps");
            object(mmv, "mapPlayerCountDraw");
            if (mmv.has("modes")) {
                JsonObject modes = mmv.getAsJsonObject("modes");
                if (modes.size() > 128) throw new IllegalArgumentException("模式数量超过上限");
                for (var e : modes.entrySet()) {
                    String k = e.getKey();
                    if (k == null || k.isBlank() || k.length() > 128 || !e.getValue().isJsonObject())
                        throw new IllegalArgumentException("无效的模式配置项: " + k);
                }
            }
            if (mmv.has("maps")) {
                JsonObject maps = mmv.getAsJsonObject("maps");
                if (maps.size() > 256) throw new IllegalArgumentException("地图数量超过上限");
                for (var e : maps.entrySet()) {
                    String k = e.getKey();
                    if (k == null || k.isBlank() || k.length() > 128
                            || k.contains("..") || k.contains("/") || k.contains("\\") || k.contains(":")
                            || !e.getValue().isJsonObject()) {
                        throw new IllegalArgumentException("无效的地图配置项: " + k);
                    }
                }
            }
        }
        if (root.has("sceneMotion")) {
            JsonObject sm = root.getAsJsonObject("sceneMotion");
            object(sm, "profiles"); object(sm, "backgrounds");
            com.habitrain.core.network.ConfigUpdateAccessPolicy.validateSceneMotion(sm);
        }
    }
    private static void object(JsonObject parent, String key) {
        if (parent.has(key) && !parent.get(key).isJsonObject()) throw new IllegalArgumentException(key + "必须是对象");
    }
    private static void finiteTree(JsonElement value, int depth) {
        if (depth > 32) throw new IllegalArgumentException("配置嵌套层级过深");
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(e -> finiteTree(e.getValue(), depth + 1));
        else if (value.isJsonArray()) value.getAsJsonArray().forEach(el -> finiteTree(el, depth + 1));
        else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && !Double.isFinite(value.getAsDouble()))
            throw new IllegalArgumentException("配置不能包含 NaN 或 Infinity");
    }
}
