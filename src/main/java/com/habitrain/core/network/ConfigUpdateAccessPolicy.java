package com.habitrain.core.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.scene.SceneLimits;

import java.util.Collection;

/** Server-side authorization and JSON filtering for configuration updates. */
public final class ConfigUpdateAccessPolicy {
    private ConfigUpdateAccessPolicy() {}

    /** Server-authoritative result for the dedicated map-preview upload endpoint. */
    public enum MapPreviewUploadAccess {
        ALLOWED,
        OP2_REQUIRED,
        MAP_NOT_CONFIGURED
    }

    /**
     * Cheap preflight that must run before inspecting attacker-controlled JSON.
     * Every update scope requires OP2, so a non-operator can be rejected without
     * parsing scope metadata or any other part of the payload.
     */
    public static boolean mayInspectPayload(boolean hasOp2) {
        return hasOp2;
    }

    public static boolean isAllowed(ConfigUpdateScope scope, boolean hasOp2,
                                    boolean dedicatedServer, boolean menuGateEnabled,
                                    boolean menuGateAllowed) {
        if (!hasOp2) return false;
        if (scope != ConfigUpdateScope.FULL_MOD_MENU) return true;
        return !dedicatedServer || !menuGateEnabled || menuGateAllowed;
    }

    /**
     * A preview upload changes only one map-vote resource, so it follows the
     * BACKPACK_MAP_VOTE authority boundary: OP2 is sufficient and the full Mod
     * Menu allowlist is deliberately irrelevant. The target still has to be a
     * map from the server's authoritative configuration.
     */
    public static MapPreviewUploadAccess mapPreviewUploadAccess(
            boolean hasOp2, String mapId, Collection<String> configuredMapIds) {
        if (!hasOp2) return MapPreviewUploadAccess.OP2_REQUIRED;
        if (mapId == null || mapId.isBlank() || configuredMapIds == null
                || !configuredMapIds.contains(mapId)) {
            return MapPreviewUploadAccess.MAP_NOT_CONFIGURED;
        }
        return MapPreviewUploadAccess.ALLOWED;
    }

    /**
     * Drops every section that the selected backpack entry point is not allowed to change.
     * The scope comes from the client, so it narrows the request but never grants privilege.
     */
    public static String filterConfigJson(ConfigUpdateScope scope, String configJson) {
        JsonElement parsed = JsonParser.parseString(configJson == null ? "{}" : configJson);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Configuration update root must be an object");
        }
        JsonObject source = parsed.getAsJsonObject();
        source.remove("_habitrain_config_update_scope");
        source.remove("_habitrain_scene_map_key");
        if (scope == ConfigUpdateScope.FULL_MOD_MENU) {
            if (source.has("sceneMotion") && source.get("sceneMotion").isJsonObject()) {
                validateSceneMotion(source.getAsJsonObject("sceneMotion"));
            }
            return source.toString();
        }

        JsonObject filtered = new JsonObject();
        String permittedSection;
        if (scope == ConfigUpdateScope.BACKPACK_TASKS) {
            permittedSection = "tasks";
        } else if (scope == ConfigUpdateScope.BACKPACK_MAP_VOTE) {
            permittedSection = "modeMapVote";
        } else if (scope == ConfigUpdateScope.ADMIN_SCENE_TOOL) {
            permittedSection = "sceneMotion";
        } else {
            permittedSection = null;
        }

        if (permittedSection != null) {
            JsonElement section = source.get(permittedSection);
            if (section != null && section.isJsonObject()) {
                filtered.add(permittedSection, section.deepCopy());
            }
        }
        return filtered.toString();
    }

    /**
     * 管理员场景道具只能替换服务端当前地图的一个 profile。其余 profile 与全局字段
     * 从服务端权威配置复制，客户端伪造的 mapKey 或 profiles 不会越权生效。
     */
    public static String filterAdminSceneProfile(String configJson, String currentMapKey,
                                                 JsonObject authoritativeSceneMotion) {
        if (currentMapKey == null || currentMapKey.isBlank()) {
            throw new IllegalArgumentException("Current map key is unavailable");
        }
        JsonElement parsed = JsonParser.parseString(configJson == null ? "{}" : configJson);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Configuration update root must be an object");
        JsonObject source = parsed.getAsJsonObject();
        JsonObject submittedScene = source.has("sceneMotion") && source.get("sceneMotion").isJsonObject()
                ? source.getAsJsonObject("sceneMotion") : null;
        JsonObject submittedProfiles = submittedScene != null && submittedScene.has("profiles")
                && submittedScene.get("profiles").isJsonObject()
                ? submittedScene.getAsJsonObject("profiles") : null;
        JsonElement submittedProfile = submittedProfiles == null ? null : submittedProfiles.get(currentMapKey);
        if (submittedProfile == null || !submittedProfile.isJsonObject()) {
            throw new IllegalArgumentException("Current map profile is missing");
        }
        validateAdminProfile(submittedProfile.getAsJsonObject());

        JsonObject safeScene = authoritativeSceneMotion == null
                ? new JsonObject() : authoritativeSceneMotion.deepCopy();
        JsonObject safeProfiles = safeScene.has("profiles") && safeScene.get("profiles").isJsonObject()
                ? safeScene.getAsJsonObject("profiles") : new JsonObject();
        safeProfiles.add(currentMapKey, submittedProfile.deepCopy());
        safeScene.add("profiles", safeProfiles);
        JsonObject filtered = new JsonObject();
        filtered.add("sceneMotion", safeScene);
        return filtered.toString();
    }

    /** Resolves the page-selected map without allowing an OP client to create arbitrary profile keys. */
    public static String resolveAdminSceneTargetMap(String requestedMapKey, String currentMapKey,
                                                    Collection<String> configuredMapKeys) {
        String current = currentMapKey == null ? "" : currentMapKey.trim();
        String requested = requestedMapKey == null ? "" : requestedMapKey.trim();
        if (requested.isBlank()) requested = current;
        if (requested.isBlank()) throw new IllegalArgumentException("Scene map key is unavailable");
        if ("__default__".equals(requested) || requested.equals(current)
                || configuredMapKeys != null && configuredMapKeys.contains(requested)) {
            return requested;
        }
        throw new IllegalArgumentException("Scene map key is not configured");
    }

    private static void validateAdminProfile(JsonObject profile) {
        validateFinite(profile);
        if (profile.has("dimension") && !isResourceId(profile.get("dimension").getAsString())) {
            throw new IllegalArgumentException("Invalid dimension resource id");
        }
        if (profile.has("direction")) {
            var direction = profile.getAsJsonArray("direction");
            if (direction.size() != 3) throw new IllegalArgumentException("Direction must have 3 values");
            double x = direction.get(0).getAsDouble(), y = direction.get(1).getAsDouble(), z = direction.get(2).getAsDouble();
            if (x * x + y * y + z * z < 1.0e-8) throw new IllegalArgumentException("Direction must be non-zero");
        }
        if (profile.has("sourceBounds") && profile.get("sourceBounds").isJsonObject()) {
            JsonObject bounds = profile.getAsJsonObject("sourceBounds");
            if (bounds.has("min") && bounds.has("maxExclusive")) {
                var min = bounds.getAsJsonArray("min");
                var max = bounds.getAsJsonArray("maxExclusive");
                if (min.size() != 3 || max.size() != 3) throw new IllegalArgumentException("Bounds must have 3 axes");
                for (int i = 0; i < 3; i++) {
                    long lo = min.get(i).getAsLong(), hi = max.get(i).getAsLong();
                    if (Math.abs(lo) > 30_000_000L || Math.abs(hi) > 30_000_000L
                            || hi < lo || hi - lo > SceneLimits.MAX_AXIS_LENGTH) {
                        throw new IllegalArgumentException("Source bounds are outside the allowed range");
                    }
                }
            }
        }
        requireRange(profile, "speedBlocksPerSecond", 0.0, 64.0);
        if (profile.has("loop")) {
            JsonObject loop = profile.getAsJsonObject("loop");
            requireRange(loop, "distanceBlocks", 1.0, 4096.0);
            if (loop.has("copies") && loop.get("copies").getAsInt() != 2) {
                throw new IllegalArgumentException("Loop copies must be 2");
            }
        }
        if (profile.has("render")) requireRange(profile.getAsJsonObject("render"), "maxDistanceBlocks", 32.0, 512.0);
        if (profile.has("outsideSound")) {
            JsonObject sound = profile.getAsJsonObject("outsideSound");
            if (sound.has("soundId") && !isResourceId(sound.get("soundId").getAsString())) {
                throw new IllegalArgumentException("Invalid sound resource id");
            }
            requireRange(sound, "volume", 0.0, 1.0);
            requireRange(sound, "pitch", 0.5, 2.0);
            requireRange(sound, "fadeTicks", 0.0, 200.0);
        }
        if (profile.has("shake")) {
            JsonObject shake = profile.getAsJsonObject("shake");
            requireRange(shake, "translationAmplitude", 0.0, 0.15);
            requireRange(shake, "rotationAmplitudeDegrees", 0.0, 1.5);
            requireRange(shake, "frequencyHz", 0.1, 8.0);
        }
    }

    private static void validateFinite(JsonElement element) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) validateFinite(child);
        } else if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) validateFinite(entry.getValue());
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            double value = element.getAsDouble();
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite number is not allowed");
        }
    }

    private static void validateSceneMotion(JsonObject scene) {
        validateFinite(scene);
        if (scene.has("schemaVersion") && scene.get("schemaVersion").getAsInt() != 1) {
            throw new IllegalArgumentException("Unsupported sceneMotion schemaVersion");
        }
        if (!scene.has("profiles")) return;
        if (!scene.get("profiles").isJsonObject()) throw new IllegalArgumentException("sceneMotion.profiles must be an object");
        JsonObject profiles = scene.getAsJsonObject("profiles");
        if (profiles.size() > 256) throw new IllegalArgumentException("Too many scene profiles");
        for (var entry : profiles.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().length() > 256
                    || !entry.getValue().isJsonObject()) {
                throw new IllegalArgumentException("Invalid scene profile entry");
            }
            validateAdminProfile(entry.getValue().getAsJsonObject());
        }
    }

    private static void requireRange(JsonObject object, String key, double min, double max) {
        if (!object.has(key)) return;
        double value = object.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(key + " is outside the allowed range");
        }
    }

    private static boolean isResourceId(String value) {
        return value != null && value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+");
    }
}
