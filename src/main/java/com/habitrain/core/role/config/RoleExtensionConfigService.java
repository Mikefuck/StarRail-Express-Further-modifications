package com.habitrain.core.role.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-authoritative v2 role-extension config (fix-doc §13.1).
 *
 * <p>Persisted to the independent, versioned file {@code config/habitrain_role_v2.json}
 * so OP-only toggles are not overridable through the client-mergeable main config
 * C2S path and do not pollute the client config disk. Enablement resolution is
 * additive (global → provider → entry); conflict winners pick the winning
 * {@code MODIFY} entry per {@code target#field} and are applied by the compiler.
 *
 * <p>Loaded at {@code HabiTrainCore} init and re-read at {@code SERVER_STARTED} so
 * edits made from the server console persist across an integrated-server restart.
 * Unit tests mutate the in-memory section directly (no FabricLoader call) and call
 * {@link #resetForTests()} in {@code @BeforeEach}.
 */
public final class RoleExtensionConfigService {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleExtensionConfig");
    private static final String FILE_NAME = "habitrain_role_v2.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final RoleExtensionConfigService INSTANCE = new RoleExtensionConfigService();

    private RoleExtensionConfigSection section = RoleExtensionConfigSection.createDefault();
    private volatile @Nullable File fileOverride;

    private RoleExtensionConfigService() {}

    /** Where the config file lives; the FabricLoader config dir unless a test redirects it. */
    private File file() {
        File override = fileOverride;
        return override != null ? override : new File(
                FabricLoader.getInstance().getConfigDir().toFile(), FILE_NAME);
    }

    // ------------------------------------------------------------------
    // Enablement resolution
    // ------------------------------------------------------------------

    /** Why an entry is (or is not) effective. */
    public enum EntryGate {
        /** Fully enabled by global + provider + entry config. */
        ENABLED,
        /** The global {@code enabled} switch is off. */
        GLOBAL_DISABLED,
        /** The owning provider is disabled. */
        PROVIDER_DISABLED,
        /** The entry is explicitly disabled. */
        ENTRY_DISABLED
    }

    public boolean isEnabled() {
        return section.isEnabled();
    }

    public boolean isProviderEnabled(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return true;
        }
        Map<String, Boolean> providers = section.providers();
        return !providers.containsKey(providerId) || providers.get(providerId);
    }

    public boolean isEntryEnabled(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return true;
        }
        Map<String, Boolean> entries = section.entries();
        return !entries.containsKey(entryId) || entries.get(entryId);
    }

    /** The additive gate for a compiled entry (global → provider → entry). */
    public EntryGate gateFor(ManagedRoleEntry<?> entry) {
        return gateFor(entry.providerId(), entry.entryId());
    }

    /** The additive gate given an owning provider id and a core entry id. */
    public EntryGate gateFor(String providerId, String entryId) {
        if (!isEnabled()) {
            return EntryGate.GLOBAL_DISABLED;
        }
        if (!isProviderEnabled(providerId)) {
            return EntryGate.PROVIDER_DISABLED;
        }
        if (!isEntryEnabled(entryId)) {
            return EntryGate.ENTRY_DISABLED;
        }
        return EntryGate.ENABLED;
    }

    public boolean isAllowGlobalHooks() {
        return section.isAllowGlobalHooks();
    }

    /**
     * The winning {@code MODIFY} entryId for a {@code target#field} conflict, or
     * {@code null} when no winner is configured (order decides).
     */
    public @Nullable String winnerFor(String targetField) {
        return targetField == null ? null : section.conflictWinners().get(targetField);
    }

    /** The winning entryId for a {@code target#field} key built from a role id. */
    public @Nullable String winnerFor(net.minecraft.resources.ResourceLocation target, String fieldKey) {
        if (target == null || fieldKey == null) {
            return null;
        }
        return winnerFor(target.getNamespace() + ":" + target.getPath() + "#" + fieldKey);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public void load() {
        File target = file();
        if (!target.exists()) {
            save();
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(target), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject sectionJson = root.has("roleExtensionsV2")
                    ? root.getAsJsonObject("roleExtensionsV2") : root;
            RoleExtensionConfigSection parsed = parse(sectionJson);
            this.section = parsed;
            LOGGER.info("已加载角色扩展配置: enabled={}, providers={}, entries={}, winners={}",
                    parsed.isEnabled(), parsed.providers().size(), parsed.entries().size(),
                    parsed.conflictWinners().size());
        } catch (Exception e) {
            LOGGER.error("加载角色扩展配置失败，使用默认值", e);
            this.section = RoleExtensionConfigSection.createDefault();
        }
    }

    public void save() {
        try {
            File target = file();
            if (target.getParentFile() != null && !target.getParentFile().exists()) {
                target.getParentFile().mkdirs();
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8)) {
                GSON.toJson(toJson(section), writer);
            }
        } catch (Exception e) {
            LOGGER.error("保存角色扩展配置失败", e);
        }
    }

    /** The current section as the {@code roleExtensionsV2} JSON object. */
    public JsonObject toJson(RoleExtensionConfigSection s) {
        JsonObject root = new JsonObject();
        root.addProperty("version", RoleExtensionConfigSection.VERSION);
        JsonObject sectionJson = new JsonObject();
        sectionJson.addProperty("enabled", s.isEnabled());
        JsonObject providers = new JsonObject();
        s.providers().forEach((k, v) -> providers.addProperty(k, v));
        sectionJson.add("providers", providers);
        JsonObject entries = new JsonObject();
        s.entries().forEach((k, v) -> entries.addProperty(k, v));
        sectionJson.add("entries", entries);
        JsonObject winners = new JsonObject();
        s.conflictWinners().forEach((k, v) -> winners.addProperty(k, v));
        sectionJson.add("conflictWinners", winners);
        sectionJson.addProperty("allowGlobalHooks", s.isAllowGlobalHooks());
        root.add("roleExtensionsV2", sectionJson);
        return root;
    }

    /** The current section serialized to JSON (for commands / manifest / C2S). */
    public String toJsonString() {
        return GSON.toJson(toJson(section));
    }

    /** Serializes an arbitrary section (client page rebuilds before sending C2S). */
    public static String toJsonString(RoleExtensionConfigSection section) {
        return GSON.toJson(INSTANCE.toJson(section == null ? RoleExtensionConfigSection.createDefault() : section));
    }

    /**
     * Parses a {@code roleExtensionsV2} section (or a full root containing one)
     * into a fresh section without touching the service's live state. Throws
     * {@link RuntimeException} on unparsable JSON.
     */
    public static RoleExtensionConfigSection parseSection(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject sectionJson = root.has("roleExtensionsV2")
                ? root.getAsJsonObject("roleExtensionsV2") : root;
        return parse(sectionJson);
    }

    /**
     * Applies a {@code roleExtensionsV2} JSON object or a full root (containing
     * {@code roleExtensionsV2}). Returns {@code false} (leaving the section
     * untouched) when the JSON is unparsable or has no valid section.
     */
    public boolean applyFromJson(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject sectionJson = root.has("roleExtensionsV2")
                    ? root.getAsJsonObject("roleExtensionsV2") : root;
            RoleExtensionConfigSection parsed = parse(sectionJson);
            this.section = parsed;
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("角色扩展配置 JSON 无效，未应用: {}", e.toString());
            return false;
        }
    }

    private static RoleExtensionConfigSection parse(JsonObject o) {
        RoleExtensionConfigSection s = RoleExtensionConfigSection.createDefault();
        if (o.has("enabled")) {
            s.setEnabled(o.get("enabled").getAsBoolean());
        }
        if (o.has("providers") && o.get("providers").isJsonObject()) {
            for (var e : o.getAsJsonObject("providers").entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    s.setProviderEnabled(e.getKey(), e.getValue().getAsBoolean());
                }
            }
        }
        if (o.has("entries") && o.get("entries").isJsonObject()) {
            for (var e : o.getAsJsonObject("entries").entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    s.setEntryEnabled(e.getKey(), e.getValue().getAsBoolean());
                }
            }
        }
        if (o.has("conflictWinners") && o.get("conflictWinners").isJsonObject()) {
            for (var e : o.getAsJsonObject("conflictWinners").entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    s.setConflictWinner(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        if (o.has("allowGlobalHooks")) {
            s.setAllowGlobalHooks(o.get("allowGlobalHooks").getAsBoolean());
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Mutators (save after each so OP edits persist immediately)
    // ------------------------------------------------------------------

    public void setEnabled(boolean enabled) {
        section.setEnabled(enabled);
        save();
    }

    public void setProviderEnabled(String providerId, boolean on) {
        section.setProviderEnabled(providerId, on);
        save();
    }

    public void setEntryEnabled(String entryId, boolean on) {
        section.setEntryEnabled(entryId, on);
        save();
    }

    public void setConflictWinner(String targetField, @Nullable String winnerEntryId) {
        section.setConflictWinner(targetField, winnerEntryId);
        save();
    }

    public void setAllowGlobalHooks(boolean on) {
        section.setAllowGlobalHooks(on);
        save();
    }

    /** The live section (read-only view for commands / UI). */
    public RoleExtensionConfigSection section() {
        return section.copy();
    }

    // ------------------------------------------------------------------
    // Test isolation
    // ------------------------------------------------------------------

    /** Redirects persistence for unit tests; {@code null} restores the default file. */
    public void setConfigFileForTests(@Nullable File file) {
        this.fileOverride = file;
    }

    /** Restores default in-memory state and the default file path. */
    public void resetForTests() {
        this.section = RoleExtensionConfigSection.createDefault();
        this.fileOverride = null;
    }

    /** Convenience for {@link LinkedHashMap} building in commands. */
    public static Map<String, Boolean> toggleMap() {
        return new LinkedHashMap<>();
    }
}
