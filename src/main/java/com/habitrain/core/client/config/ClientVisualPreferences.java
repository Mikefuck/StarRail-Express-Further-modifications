package com.habitrain.core.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.persist.AtomicJsonFiles;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Client-only visual preferences that must not be synchronized with a server.
 *
 * <p>The main {@code habitrain_core.json} is server-authoritative and participates in
 * config synchronization. Title-screen visuals are a local choice, so they live in a
 * separate file and are applied immediately.</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientVisualPreferences {
    private static final String FILE_NAME = "habitrain_core-client.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final boolean DEFAULT_CUSTOM_TITLE_PANORAMA_ENABLED = true;

    private static boolean loaded;
    private static boolean customTitlePanoramaEnabled = DEFAULT_CUSTOM_TITLE_PANORAMA_ENABLED;

    private ClientVisualPreferences() {
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;

        AtomicJsonFiles.JsonLoad<JsonObject> result = AtomicJsonFiles.readJson(
                file(), JsonObject.class, GSON);
        if (!result.ok()) {
            if (result.corrupt()) {
                HabiTrainCore.LOGGER.warn("客户端视觉配置损坏，已回退为默认启用自定义主菜单全景");
            }
            customTitlePanoramaEnabled = DEFAULT_CUSTOM_TITLE_PANORAMA_ENABLED;
            return;
        }

        JsonObject root = result.value();
        customTitlePanoramaEnabled = !root.has("customTitlePanoramaEnabled")
                ? DEFAULT_CUSTOM_TITLE_PANORAMA_ENABLED
                : root.get("customTitlePanoramaEnabled").isJsonPrimitive()
                        && root.get("customTitlePanoramaEnabled").getAsBoolean();
    }

    public static synchronized boolean isCustomTitlePanoramaEnabled() {
        load();
        return customTitlePanoramaEnabled;
    }

    /**
     * Applies and persists the preference. A failed write restores the previous value so
     * the Mod Menu control never claims a state that will disappear after restart.
     */
    public static synchronized boolean setCustomTitlePanoramaEnabled(boolean enabled) {
        load();
        boolean previous = customTitlePanoramaEnabled;
        customTitlePanoramaEnabled = enabled;
        if (save()) return true;
        customTitlePanoramaEnabled = previous;
        return false;
    }

    private static boolean save() {
        JsonObject root = new JsonObject();
        root.addProperty("customTitlePanoramaEnabled", customTitlePanoramaEnabled);
        boolean saved = AtomicJsonFiles.writeJson(file(), root, GSON, true);
        if (!saved) {
            HabiTrainCore.LOGGER.error("无法保存客户端视觉配置 {}", file());
        }
        return saved;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
