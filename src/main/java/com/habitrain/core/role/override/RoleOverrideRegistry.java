package com.habitrain.core.role.override;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.ReplaceRoleDefinition;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class RoleOverrideRegistry {
    public static final RoleOverrideRegistry INSTANCE = new RoleOverrideRegistry();
    private static final Logger LOGGER = LoggerFactory.getLogger("RoleOverrideRegistry");

    private final List<ReplaceRoleDefinition> replaces = new ArrayList<>();
    private final List<ModifyRoleDefinition> modifies = new ArrayList<>();
    private final Set<String> registeredEntryIds = new HashSet<>();
    private final Map<ResourceLocation, String> replacementOwners = new HashMap<>();
    private boolean frozen = false;

    private RoleOverrideRegistry() {}

    public static void init() { LOGGER.info("RoleOverrideRegistry initialized"); }

    public void registerReplace(ReplaceRoleDefinition def) {
        validateDefinition(def);
        if (frozen) throw new IllegalStateException("Role override registry is frozen");
        String entryId = entryId(def);
        if (!registeredEntryIds.add(entryId)) {
            throw new IllegalArgumentException("Duplicate role override entryId: " + entryId);
        }
        ResourceLocation replacementId = def.replacementRole().identifier();
        String previousOwner = replacementOwners.putIfAbsent(replacementId, entryId);
        if (previousOwner != null) {
            registeredEntryIds.remove(entryId);
            throw new IllegalArgumentException("Replacement role id " + replacementId
                    + " is already managed by " + previousOwner);
        }
        replaces.add(def);
        LOGGER.info("Registered REPLACE: {} -> {}", def.targetRoleId(), def.replacementRole().identifier());
    }

    public void registerModify(ModifyRoleDefinition def) {
        validateDefinition(def);
        if (frozen) throw new IllegalStateException("Role override registry is frozen");
        String entryId = entryId(def);
        if (!registeredEntryIds.add(entryId)) {
            throw new IllegalArgumentException("Duplicate role override entryId: " + entryId
                    + "; set a distinct entryKey for each declaration");
        }
        modifies.add(def);
        LOGGER.info("Registered MODIFY: {}", def.targetRoleId());
    }

    private void validateDefinition(ReplaceRoleDefinition def) {
        if (def.sourceModId() == null || def.sourceModId().isBlank()) {
            throw new IllegalArgumentException("sourceModId required");
        }
        if (FabricLoader.getInstance().getModContainer(def.sourceModId()).isEmpty()) {
            throw new IllegalArgumentException("sourceModId " + def.sourceModId() + " not loaded");
        }
        if (def.replacementRole() == null) throw new IllegalArgumentException("replacementRole required");
        ResourceLocation id = def.replacementRole().identifier();
        if (id == null) throw new IllegalArgumentException("replacementRole must have an identifier");
        if (def.replacementId().isEmpty() || !def.replacementId().get().equals(id)) {
            throw new IllegalArgumentException("replacementId must equal replacementRole.identifier(): " + id);
        }
        if (!def.sourceModId().equals(id.getNamespace())) {
            throw new IllegalArgumentException("replacementRole id namespace must match sourceModId: " + id);
        }
        validateEntryKey(def.sourceModId(), def.entryKey());
    }

    private void validateDefinition(ModifyRoleDefinition def) {
        if (def.sourceModId() == null || def.sourceModId().isBlank()) {
            throw new IllegalArgumentException("sourceModId required");
        }
        if (FabricLoader.getInstance().getModContainer(def.sourceModId()).isEmpty()) {
            throw new IllegalArgumentException("sourceModId " + def.sourceModId() + " not loaded");
        }
        if (def.skillRegistrar().isPresent() && def.managedSkillPatch().isPresent()) {
            throw new IllegalArgumentException(
                    "Choose either legacy skillRegistrar or managedSkillPatch, not both");
        }
        validateEntryKey(def.sourceModId(), def.entryKey());
    }

    private static void validateEntryKey(String sourceModId, Optional<String> entryKey) {
        if (entryKey.isEmpty()) return;
        String key = entryKey.get();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("entryKey must not be blank");
        }
        try {
            ResourceLocation.fromNamespaceAndPath(sourceModId, key);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid entryKey for " + sourceModId + ": " + key, e);
        }
    }

    public static String entryId(ReplaceRoleDefinition def) {
        ResourceLocation replId = def.replacementRole().identifier();
        String key = def.entryKey().orElse(replId.getPath());
        return def.sourceModId() + "$" + key + "@" + def.targetRoleId();
    }

    public static String entryId(ModifyRoleDefinition def) {
        String key = def.entryKey().orElse(def.targetRoleId().getPath());
        return def.sourceModId() + "$" + key + "@" + def.targetRoleId();
    }

    public List<ReplaceRoleDefinition> getReplaces() { return Collections.unmodifiableList(replaces); }
    public List<ModifyRoleDefinition> getModifies() { return Collections.unmodifiableList(modifies); }

    public void freeze() { this.frozen = true; }
}
