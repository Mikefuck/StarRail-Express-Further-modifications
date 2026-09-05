package com.habitrain.core.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.scene.SceneLimits;
import com.habitrain.core.config.SceneMotionSettings;
import com.habitrain.core.scene.model.SceneLoopDistanceMode;
import com.habitrain.core.scene.model.SceneMotionMode;
import com.habitrain.core.scene.model.SceneOrbitAxis;
import com.habitrain.core.scene.model.SceneOrbitCenterMode;
import com.habitrain.core.scene.model.SceneOrbitSettings;

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
        JsonObject submittedMapBackgrounds = null;
        if (submittedScene.has("backgrounds") && submittedScene.get("backgrounds").isJsonObject()) {
            JsonElement submittedMapElement = submittedScene.getAsJsonObject("backgrounds").get(currentMapKey);
            if (submittedMapElement != null) {
                if (!submittedMapElement.isJsonObject()) {
                    throw new IllegalArgumentException("Current map backgrounds must be an object");
                }
                submittedMapBackgrounds = submittedMapElement.getAsJsonObject();
                validateBackgrounds(submittedMapBackgrounds);
            }
        }

        JsonObject safeScene = authoritativeSceneMotion == null
                ? new JsonObject() : authoritativeSceneMotion.deepCopy();
        JsonObject safeProfiles = safeScene.has("profiles") && safeScene.get("profiles").isJsonObject()
                ? safeScene.getAsJsonObject("profiles") : new JsonObject();
        safeProfiles.add(currentMapKey, submittedProfile.deepCopy());
        safeScene.add("profiles", safeProfiles);
        JsonObject safeBackgrounds = safeScene.has("backgrounds") && safeScene.get("backgrounds").isJsonObject()
                ? safeScene.getAsJsonObject("backgrounds") : new JsonObject();
        if (submittedMapBackgrounds == null || submittedMapBackgrounds.size() == 0) {
            safeBackgrounds.remove(currentMapKey);
        } else {
            safeBackgrounds.add(currentMapKey, submittedMapBackgrounds.deepCopy());
        }
        safeScene.add("backgrounds", safeBackgrounds);
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
            if (loop.has("distanceMode")) {
                String mode = loop.get("distanceMode").getAsString();
                if (!mode.equals(SceneLoopDistanceMode.AUTO.name())
                        && !mode.equals(SceneLoopDistanceMode.CUSTOM.name())) {
                    throw new IllegalArgumentException("Unsupported loop distanceMode");
                }
            }
            requireRange(loop, "distanceBlocks", 1.0, 4096.0);
            if (loop.has("copies") && loop.get("copies").getAsInt() != 2) {
                throw new IllegalArgumentException("Loop copies must be 2");
            }
        }
        if (profile.has("motionMode")) {
            String mode = profile.get("motionMode").getAsString();
            if (!mode.equals(SceneMotionMode.LINEAR.name()) && !mode.equals(SceneMotionMode.ORBIT.name())) {
                throw new IllegalArgumentException("Unsupported motionMode: " + mode);
            }
        }
        if (profile.has("orbit")) {
            if (!profile.get("orbit").isJsonObject()) {
                throw new IllegalArgumentException("Orbit settings must be an object");
            }
            JsonObject orbit = profile.getAsJsonObject("orbit");
            if (orbit.has("centerMode")) {
                String cm = orbit.get("centerMode").getAsString();
                if (!cm.equals(SceneOrbitCenterMode.WORLD_BLOCK.name())
                        && !cm.equals(SceneOrbitCenterMode.MODEL_CENTER.name())) {
                    throw new IllegalArgumentException("Unsupported orbit centerMode");
                }
            }
            if (orbit.has("centerWorld")) {
                if (!orbit.get("centerWorld").isJsonArray()) {
                    throw new IllegalArgumentException("centerWorld must be an array");
                }
                var arr = orbit.getAsJsonArray("centerWorld");
                if (arr.size() != 3) throw new IllegalArgumentException("centerWorld must have 3 coordinates");
                for (int i = 0; i < 3; i++) {
                    double coord = arr.get(i).getAsDouble();
                    if (!Double.isFinite(coord) || Math.abs(coord) > 30_000_000.0) {
                        throw new IllegalArgumentException("centerWorld coordinate out of world bounds");
                    }
                }
            }
            if (orbit.has("axis")) {
                String axis = orbit.get("axis").getAsString();
                if (!axis.equals(SceneOrbitAxis.X.name())
                        && !axis.equals(SceneOrbitAxis.Y.name())
                        && !axis.equals(SceneOrbitAxis.Z.name())) {
                    throw new IllegalArgumentException("Unsupported orbit axis");
                }
            }
            requireRange(orbit, "startAngleDegrees", 0.0, 360.0);
            requireRange(orbit, "sweepDegrees", SceneOrbitSettings.MIN_SWEEP, SceneOrbitSettings.MAX_SWEEP);
            requireRange(orbit, "angularSpeedDegreesPerSecond", SceneOrbitSettings.MIN_SPEED, SceneOrbitSettings.MAX_SPEED);
            requireRange(orbit, "verticalBobAmplitudeBlocks", SceneOrbitSettings.MIN_BOB_AMPLITUDE, SceneOrbitSettings.MAX_BOB_AMPLITUDE);
            requireRange(orbit, "radialBobAmplitudeBlocks", SceneOrbitSettings.MIN_BOB_AMPLITUDE, SceneOrbitSettings.MAX_BOB_AMPLITUDE);
            requireRange(orbit, "bobCyclesPerSecond", SceneOrbitSettings.MIN_BOB_CYCLES, SceneOrbitSettings.MAX_BOB_CYCLES);
            if (orbit.has("instanceCount")) {
                int count = orbit.get("instanceCount").getAsInt();
                if (count < SceneOrbitSettings.MIN_INSTANCES || count > SceneOrbitSettings.MAX_INSTANCES) {
                    throw new IllegalArgumentException("instanceCount is outside allowed range");
                }
            }
            requireRange(orbit, "instanceSpreadDegrees", SceneOrbitSettings.MIN_SPREAD, SceneOrbitSettings.MAX_SPREAD);
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
        if (scene.has("schemaVersion") && scene.get("schemaVersion").getAsInt() != 1
                && scene.get("schemaVersion").getAsInt() != 2
                && scene.get("schemaVersion").getAsInt() != SceneMotionSettings.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported sceneMotion schemaVersion");
        }
        if (scene.has("profiles")) {
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
        if (scene.has("backgrounds")) {
            if (!scene.get("backgrounds").isJsonObject()) {
                throw new IllegalArgumentException("sceneMotion.backgrounds must be an object");
            }
            JsonObject maps = scene.getAsJsonObject("backgrounds");
            if (maps.size() > 256) throw new IllegalArgumentException("Too many scene background maps");
            for (var mapEntry : maps.entrySet()) {
                if (mapEntry.getKey() == null || mapEntry.getKey().isBlank()
                        || mapEntry.getKey().length() > 256 || !mapEntry.getValue().isJsonObject()) {
                    throw new IllegalArgumentException("Invalid scene background map entry");
                }
                validateBackgrounds(mapEntry.getValue().getAsJsonObject());
            }
        }
    }

    private static void validateBackgrounds(JsonObject backgrounds) {
        if (backgrounds.size() > SceneMotionSettings.MAX_BACKGROUNDS_PER_MAP - 1) {
            throw new IllegalArgumentException("A map may have at most five scene backgrounds including default");
        }
        for (var entry : backgrounds.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[a-z0-9_.-]{1,48}")
                    || "__default__".equals(entry.getKey()) || !entry.getValue().isJsonObject()) {
                throw new IllegalArgumentException("Invalid background id");
            }
            JsonObject background = entry.getValue().getAsJsonObject();
            if (!background.has("name") || background.get("name").getAsString().isBlank()
                    || background.get("name").getAsString().length() > 32) {
                throw new IllegalArgumentException("Invalid background name");
            }
            if (!background.has("profile") || !background.get("profile").isJsonObject()) {
                throw new IllegalArgumentException("Background profile is missing");
            }
            validateAdminProfile(background.getAsJsonObject("profile"));
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
