package com.habitrain.core.config;

import com.google.gson.*;
import com.habitrain.core.network.ConfigUpdateScope;
import java.util.*;

/** Version 1 delta: missing paths are unchanged; remove restores inheritance/deletes a key. */
public final class ConfigPatch {
    public static final int PROTOCOL = 1;
    public static final int MAX_BYTES = 30_000; // leaves room for the header below vanilla C2S custom payload limits
    public static final int MAX_SYNC_JSON_BYTES = 1_000_000; // leave room for ACK fields, metadata and packet headers
    private static final Set<String> SECTIONS = Set.of("global", "tasks", "gameModes", "minigames", "modeMapVote", "environment", "sceneMotion");
    private ConfigPatch() {}
    public static JsonObject diff(JsonObject before, JsonObject after) {
        JsonObject patch = new JsonObject();
        JsonObject set = new JsonObject(); JsonArray remove = new JsonArray();
        diffObject("", before, after, set, remove);
        patch.add("set", set); patch.add("remove", remove);
        return patch;
    }
    private static void diffObject(String path, JsonObject old, JsonObject next, JsonObject set, JsonArray remove) {
        for (var entry : old.entrySet()) if (!next.has(entry.getKey())) remove.add(path + "/" + escape(entry.getKey()));
        for (var entry : next.entrySet()) {
            String child = path + "/" + escape(entry.getKey()); JsonElement prior = old.get(entry.getKey()); JsonElement value = entry.getValue();
            if (prior != null && prior.toString().equals(value.toString())) continue;
            if ((child.equals("/modeMapVote/modes") || child.equals("/modeMapVote/maps"))
                    && prior != null && prior.isJsonObject() && value.isJsonObject()
                    && !new ArrayList<>(prior.getAsJsonObject().keySet()).equals(new ArrayList<>(value.getAsJsonObject().keySet()))) {
                set.add(child, value.deepCopy()); continue;
            }
            if (prior != null && prior.isJsonObject() && value.isJsonObject()) diffObject(child, prior.getAsJsonObject(), value.getAsJsonObject(), set, remove);
            else set.add(child, value.deepCopy());
        }
    }
    public static boolean empty(JsonObject patch) { return patch.getAsJsonObject("set").size() == 0 && patch.getAsJsonArray("remove").isEmpty(); }
    public static JsonObject apply(JsonObject current, JsonObject patch, ConfigUpdateScope scope, String sceneMap) {
        JsonObject result = current.deepCopy();
        if (!patch.has("set") || !patch.get("set").isJsonObject() || !patch.has("remove") || !patch.get("remove").isJsonArray())
            throw new IllegalArgumentException("patch 必须包含 set 对象与 remove 数组");
        if (patch.getAsJsonObject("set").size() + patch.getAsJsonArray("remove").size() > 4096) throw new IllegalArgumentException("修改字段过多");
        for (var entry : patch.getAsJsonObject("set").entrySet()) change(result, path(entry.getKey(), scope, sceneMap), entry.getValue(), false);
        for (JsonElement entry : patch.getAsJsonArray("remove")) change(result, path(entry.getAsString(), scope, sceneMap), null, true);
        ConfigValidation.validate(result);
        return result;
    }
    public static JsonObject filtered(JsonObject patch, ConfigUpdateScope scope, String sceneMap) {
        JsonObject result = new JsonObject(); JsonObject set = new JsonObject(); JsonArray remove = new JsonArray();
        patch.getAsJsonObject("set").entrySet().forEach(entry -> { if (permitted(entry.getKey(), scope, sceneMap)) set.add(entry.getKey(), entry.getValue().deepCopy()); });
        patch.getAsJsonArray("remove").forEach(entry -> { if (permitted(entry.getAsString(), scope, sceneMap)) remove.add(entry.deepCopy()); });
        result.add("set", set); result.add("remove", remove); return result;
    }
    private static boolean permitted(String value, ConfigUpdateScope scope, String map) {
        try { path(value, scope, map); return true; } catch (IllegalArgumentException e) { return false; }
    }
    private static List<String> path(String value, ConfigUpdateScope scope, String map) {
        if (value == null || !value.startsWith("/") || value.length() > 4096) throw new IllegalArgumentException("无效修改路径");
        List<String> parts = Arrays.stream(value.substring(1).split("/", -1)).map(s -> s.replace("~1", "/").replace("~0", "~")).toList();
        if (parts.isEmpty() || !SECTIONS.contains(parts.getFirst())) throw new IllegalArgumentException("不允许修改此配置字段");
        boolean allowed = switch (scope) {
            case FULL_MOD_MENU -> true;
            case BACKPACK_TASKS -> parts.getFirst().equals("tasks");
            case BACKPACK_MAP_VOTE -> parts.getFirst().equals("modeMapVote");
            case ADMIN_SCENE_TOOL -> parts.size() >= 3 && parts.getFirst().equals("sceneMotion")
                    && Set.of("profiles", "backgrounds").contains(parts.get(1)) && Objects.equals(parts.get(2), map);
        };
        if (!allowed) throw new IllegalArgumentException("修改超出页面权限范围");
        return parts;
    }
    private static void change(JsonObject root, List<String> path, JsonElement value, boolean remove) {
        JsonObject parent = root;
        for (int i = 0; i < path.size() - 1; i++) {
            String key = path.get(i);
            if (!parent.has(key)) { if (remove) return; parent.add(key, new JsonObject()); }
            if (!parent.get(key).isJsonObject()) throw new IllegalArgumentException("修改路径的父节点必须是对象");
            parent = parent.getAsJsonObject(key);
        }
        String key = path.getLast();
        if (remove) parent.remove(key); else { if (value == null || value.isJsonNull()) throw new IllegalArgumentException("清空使用 remove，不能用 null"); parent.add(key, value.deepCopy()); }
    }
    private static String escape(String key) { return key.replace("~", "~0").replace("/", "~1"); }
}
