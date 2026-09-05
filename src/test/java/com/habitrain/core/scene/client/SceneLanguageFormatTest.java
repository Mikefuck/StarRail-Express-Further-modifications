package com.habitrain.core.scene.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SceneLanguageFormatTest {
    private static final Pattern UNSUPPORTED_FORMAT = Pattern.compile("%(?:\\d+\\$)?(?:\\.\\d+)?[df]");

    @Test
    void sceneTranslationsUseMinecraftSupportedPlaceholders() throws Exception {
        for (String language : new String[]{"zh_cn", "en_us"}) {
            Path path = Path.of("src", "main", "resources", "assets", "habitrain_core",
                    "lang", language + ".json");
            try (Reader reader = Files.newBufferedReader(path)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    if (!entry.getKey().contains("scene_motion")
                            && !entry.getKey().contains("scene_tool")
                            && !entry.getKey().contains("scene_view_distance")) continue;
                    String value = entry.getValue().getAsString();
                    assertFalse(UNSUPPORTED_FORMAT.matcher(value).find(),
                            () -> language + " uses an unsupported translation placeholder in "
                                    + entry.getKey() + ": " + value);
                }
            }
        }
    }

    @Test
    void compatibilityIssueLabelsExistInBothLanguages() throws Exception {
        Set<String> suffixes = Set.of(
                "none", "missing_texture", "invalid_atlas", "abnormal_packed_light",
                "missing_adapter", "unsupported_render_path", "skipped");
        for (String language : new String[]{"zh_cn", "en_us"}) {
            Path path = Path.of("src", "main", "resources", "assets", "habitrain_core",
                    "lang", language + ".json");
            try (Reader reader = Files.newBufferedReader(path)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                for (String suffix : suffixes) {
                    String key = "screen.habitrain_core.scene_motion.compat_issue_" + suffix;
                    assertTrue(root.has(key), () -> language + " is missing " + key);
                    assertFalse(root.get(key).getAsString().isBlank(), () -> language + " has blank " + key);
                }
            }
        }
    }
}
