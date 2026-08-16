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
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

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
    private volatile @Nullable Supplier<File> fileResolverForTests;
    private volatile @Nullable String lastSaveError;

    private RoleExtensionConfigService() {}

    /**
     * Where the config file lives; the FabricLoader config dir unless a test
     * redirects it. Returns {@code null} when no config dir is available (bare
     * unit-test JVM / hostile environment) — {@link #save()} then degrades to a
     * visible in-memory-only no-op instead of throwing.
     */
    private @Nullable File file() {
        Supplier<File> resolver = fileResolverForTests;
        if (resolver != null) {
            return resolver.get();
        }
        File override = fileOverride;
        if (override != null) {
            return override;
        }
        try {
            java.nio.file.Path dir = FabricLoader.getInstance().getConfigDir();
            return dir == null ? null : new File(dir.toFile(), FILE_NAME);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The last persistence failure, or {@code null} when the most recent
     * {@link #save()} succeeded (or was skipped without error). Surfaced by
     * {@code /habitrain roleapi config status} so silent save failures
     * (e.g. an unwritable config dir) are diagnosable instead of looking like
     * the toggles simply "not working".
     */
    public @Nullable String lastSaveError() {
        return lastSaveError;
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

    /** The additive gate for a provider-scoped action/state/voice/chat declaration (audit P1-2). */
    public <T> EntryGate gateFor(com.habitrain.core.role.extension.ManagedDeclaration<T> declaration) {
        if (declaration == null) {
            return EntryGate.ENABLED;
        }
        return gateFor(declaration.providerId(), declaration.entryId());
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
        if (target == null) {
            // No usable config dir (bare unit-test JVM / hostile environment):
            // keep the in-memory defaults, do not throw, do not attempt a save.
            this.section = RoleExtensionConfigSection.createDefault();
            LOGGER.warn("角色扩展配置目录不可用; 保持内存默认值 (lastSaveError={})", lastSaveError);
            return;
        }
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
            if (renameCorrupt(target)) {
                // Original corrupt file preserved under a .corrupt.* name; write defaults atomically.
                save();
            } else {
                // Preserve the corrupt file as-is; never silently overwrite the evidence.
                lastSaveError = "无法重命名损坏的配置文件: " + target.getName();
            }
        }
    }

    /**
     * Renames a corrupt config file to a unique {@code <name>.corrupt.<timestamp>[.<i>]}
     * sibling so the broken content survives for inspection. Never overwrites an
     * existing backup. Returns {@code false} (leaving the original untouched) when
     * the move fails — e.g. read-only config dir.
     */
    private boolean renameCorrupt(File target) {
        Path p = target.toPath();
        Path parent = p.toAbsolutePath().getParent();
        String name = p.getFileName().toString();
        long ts = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            Path dest = parent.resolve(name + ".corrupt." + ts + (i == 0 ? "" : "." + i));
            if (Files.exists(dest)) {
                continue;
            }
            try {
                Files.move(p, dest);
                LOGGER.warn("损坏的配置已保留为 {}", dest);
                return true;
            } catch (IOException e) {
                LOGGER.error("无法重命名损坏的配置文件 {}", target, e);
                return false;
            }
        }
        return false;
    }

    /**
     * Persists the section with an atomic same-directory write: temp file →
     * flush → fsync → atomic move over the target, falling back to a plain
     * move where the filesystem lacks {@code ATOMIC_MOVE}. A crash mid-write
     * can therefore never leave a truncated {@code habitrain_role_v2.json};
     * the previous good file survives until the replacement lands.
     */
    public void save() {
        File target = file();
        if (target == null) {
            lastSaveError = "no config dir (non-Fabric environment); changes kept in memory only";
            LOGGER.warn("角色扩展配置未落盘: {}", lastSaveError);
            return;
        }
        Path targetPath = target.toPath();
        Path dir = targetPath.toAbsolutePath().getParent();
        try {
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = Files.createTempFile(dir, target.getName(), ".tmp");
            try {
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
                    GSON.toJson(toJson(section), writer);
                }
                try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                try {
                    Files.move(tmp, targetPath, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                lastSaveError = null;
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            lastSaveError = e.toString();
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

    /**
     * Test-only seam for exercising the {@code null} file-target path (and other
     * exotic resolvers) that {@link #setConfigFileForTests} cannot express.
     * Package-private: never part of the public API. {@code null} restores the
     * default resolution.
     */
    void setFileResolverForTests(@Nullable Supplier<File> resolver) {
        this.fileResolverForTests = resolver;
    }

    /** Restores default in-memory state and the default file path. */
    public void resetForTests() {
        this.section = RoleExtensionConfigSection.createDefault();
        this.fileOverride = null;
        this.fileResolverForTests = null;
        this.lastSaveError = null;
    }

    /** Convenience for {@link LinkedHashMap} building in commands. */
    public static Map<String, Boolean> toggleMap() {
        return new LinkedHashMap<>();
    }
}
