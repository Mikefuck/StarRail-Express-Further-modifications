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
    private boolean frozen = false;

    private RoleOverrideRegistry() {}

    public static void init() { LOGGER.info("RoleOverrideRegistry initialized"); }

    public void registerReplace(ReplaceRoleDefinition def) {
        validateDefinition(def);
        if (frozen) throw new IllegalStateException("Role override registry is frozen");
        replaces.add(def);
        LOGGER.info("Registered REPLACE: {} -> {}", def.targetRoleId(), def.replacementRole().identifier());
    }

    public void registerModify(ModifyRoleDefinition def) {
        validateDefinition(def);
        if (frozen) throw new IllegalStateException("Role override registry is frozen");
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
        if (!def.sourceModId().equals(id.getNamespace())) {
            throw new IllegalArgumentException("replacementRole id namespace must match sourceModId: " + id);
        }
    }

    private void validateDefinition(ModifyRoleDefinition def) {
        if (def.sourceModId() == null || def.sourceModId().isBlank()) {
            throw new IllegalArgumentException("sourceModId required");
        }
        if (FabricLoader.getInstance().getModContainer(def.sourceModId()).isEmpty()) {
            throw new IllegalArgumentException("sourceModId " + def.sourceModId() + " not loaded");
        }
    }

    public List<ReplaceRoleDefinition> getReplaces() { return Collections.unmodifiableList(replaces); }
    public List<ModifyRoleDefinition> getModifies() { return Collections.unmodifiableList(modifies); }

    public void freeze() { this.frozen = true; }
}
