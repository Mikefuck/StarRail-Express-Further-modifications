package com.habitrain.core.scene.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Controlled, repeatable migrations for the scene-motion configuration root. */
public final class SceneMotionMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneMotionMigration.class);
    public static final int LOOP_DISTANCE_MODE_SCHEMA = 2;
    public static final int MULTI_BACKGROUND_SCHEMA = 3;

    private SceneMotionMigration() {}

    /**
     * Migrates schema 1 to schema 2 on a deep copy. Every historical profile becomes CUSTOM so
     * its previously stored distance keeps producing the same scene spacing.
     */
    public static JsonObject migrateToCurrent(JsonObject source) {
        JsonObject migrated = source == null ? new JsonObject() : source.deepCopy();
        int sourceVersion = readSchemaVersion(migrated);
        if (sourceVersion >= MULTI_BACKGROUND_SCHEMA) return migrated;

        JsonObject profiles = migrated.has("profiles") && migrated.get("profiles").isJsonObject()
                ? migrated.getAsJsonObject("profiles") : null;
        if (profiles != null && sourceVersion < LOOP_DISTANCE_MODE_SCHEMA) {
            for (var entry : profiles.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject profObj = entry.getValue().getAsJsonObject();
                String basePath = "sceneMotion.profiles." + entry.getKey();
                migrateLegacyLoop(profObj, basePath + ".loop");
                migrateLegacyOrbit(profObj, basePath);
            }
        }
        if (!migrated.has("backgrounds") || !migrated.get("backgrounds").isJsonObject()) {
            migrated.add("backgrounds", new JsonObject());
        }
        migrated.addProperty("schemaVersion", MULTI_BACKGROUND_SCHEMA);
        LOGGER.info("移动场景配置迁移完成: sceneMotion.schemaVersion {} -> {}",
                sourceVersion, MULTI_BACKGROUND_SCHEMA);
        return migrated;
    }

    private static int readSchemaVersion(JsonObject root) {
        try {
            return root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 1;
        } catch (RuntimeException ignored) {
            LOGGER.warn("移动场景配置迁移: sceneMotion.schemaVersion 无效，按 schema 1 处理");
            return 1;
        }
    }

    private static void migrateLegacyLoop(JsonObject profile, String path) {
        JsonObject loop;
        if (profile.has("loop") && profile.get("loop").isJsonObject()) {
            loop = profile.getAsJsonObject("loop");
        } else {
            loop = new JsonObject();
            profile.add("loop", loop);
            LOGGER.info("移动场景配置迁移: {} 缺失，补为旧版默认 CUSTOM 512 格", path);
        }

        double rawDistance = readLegacyDistance(loop.get("distanceBlocks"), path + ".distanceBlocks");
        double normalized = Math.max(SceneLoopSettings.MIN_DISTANCE,
                Math.min(SceneLoopSettings.MAX_DISTANCE, rawDistance));
        if (Double.compare(rawDistance, normalized) != 0) {
            LOGGER.warn("移动场景配置迁移: {}={} 超出范围，归一化为 {}",
                    path + ".distanceBlocks", rawDistance, normalized);
        }
        loop.addProperty("distanceMode", SceneLoopDistanceMode.CUSTOM.name());
        loop.addProperty("distanceBlocks", normalized);
        loop.addProperty("copies", SceneLoopSettings.DEFAULT_COPIES);
        if (!loop.has("enabled") || !loop.get("enabled").isJsonPrimitive()) {
            loop.addProperty("enabled", true);
        }
        LOGGER.info("移动场景配置迁移: {}.distanceMode -> CUSTOM，保留区间 {} 格", path, normalized);
    }

    private static double readLegacyDistance(JsonElement value, String path) {
        if (value == null || value.isJsonNull()) {
            LOGGER.info("移动场景配置迁移: {} 缺失，采用旧版默认 {}",
                    path, SceneLoopSettings.DEFAULT_DISTANCE);
            return SceneLoopSettings.DEFAULT_DISTANCE;
        }
        try {
            double parsed = value.getAsDouble();
            if (Double.isFinite(parsed)) return parsed;
        } catch (RuntimeException ignored) {
            // Logged below with the exact field path.
        }
        LOGGER.warn("移动场景配置迁移: {} 无效，采用旧版默认 {}",
                path, SceneLoopSettings.DEFAULT_DISTANCE);
        return SceneLoopSettings.DEFAULT_DISTANCE;
    }

    private static void migrateLegacyOrbit(JsonObject profile, String basePath) {
        if (!profile.has("motionMode") || !profile.get("motionMode").isJsonPrimitive()) {
            profile.addProperty("motionMode", SceneMotionMode.LINEAR.name());
            LOGGER.info("移动场景配置迁移: {}.motionMode 缺失，默认设为 LINEAR", basePath);
        }
        if (!profile.has("orbit") || !profile.get("orbit").isJsonObject()) {
            profile.add("orbit", SceneOrbitSettings.createDefault().toJson());
            LOGGER.info("移动场景配置迁移: {}.orbit 缺失，补为默认环绕配置", basePath);
        }
    }
}
