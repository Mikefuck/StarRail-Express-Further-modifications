package com.habitrain.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

/** Recover a structurally damaged config without discarding unrelated valid fields. */
final class ConfigFieldRecovery {
    private ConfigFieldRecovery() {}

    static JsonObject recover(JsonObject damaged, JsonObject backup, Logger logger) {
        JsonObject result = backup == null ? new JsonObject() : backup.deepCopy();
        merge(result, damaged, "", result, logger);
        return result;
    }

    private static void merge(JsonObject target, JsonObject incoming, String path,
                              JsonObject root, Logger logger) {
        for (var field : incoming.entrySet()) {
            String key = field.getKey();
            String qualified = path.isEmpty() ? key : path + "." + key;
            JsonElement previous = target.get(key);
            JsonElement next = field.getValue();
            if (next.isJsonObject()) {
                JsonObject child = previous != null && previous.isJsonObject()
                        ? previous.getAsJsonObject() : new JsonObject();
                target.add(key, child);
                try {
                    ConfigValidation.validateStructure(root);
                    merge(child, next.getAsJsonObject(), qualified, root, logger);
                } catch (RuntimeException invalidContainer) {
                    restore(target, key, previous);
                    logger.warn("配置恢复：忽略无效字段 {} ({})", qualified, invalidContainer.getMessage());
                }
            } else {
                target.add(key, next.deepCopy());
                try {
                    ConfigValidation.validateStructure(root);
                } catch (RuntimeException invalidField) {
                    restore(target, key, previous);
                    logger.warn("配置恢复：忽略无效字段 {} ({})", qualified, invalidField.getMessage());
                }
            }
        }
        // Legacy fields and typed string lists also need validation before their readers run.
        if (path.equals("global")) {
            for (String key : new String[]{"dlcWeightBoost"}) {
                JsonElement value = target.get(key);
                if (value != null && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())) {
                    target.remove(key);
                    logger.warn("配置恢复：忽略无效字段 global.{}", key);
                }
            }
            JsonElement list = target.get("shaderWhitelist");
            if (list != null && list.isJsonArray()) {
                var iterator = list.getAsJsonArray().iterator();
                while (iterator.hasNext()) {
                    JsonElement item = iterator.next();
                    if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                        iterator.remove();
                        logger.warn("配置恢复：忽略 shaderWhitelist 中非字符串的条目");
                    }
                }
            }
        }
    }
    private static void restore(JsonObject target, String key, JsonElement previous) {
        if (previous == null) target.remove(key); else target.add(key, previous);
    }
}
