package com.habitrain.core.role.override;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.ReplaceRoleDefinition;
import com.habitrain.core.api.role.RoleOverrideEntry;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;

public final class EffectiveSnapshot {
    private final Map<ResourceLocation, ReplaceRoleDefinition> activeReplaces;
    private final Map<ResourceLocation, ModifyRoleDefinition> activeModifies;
    private final List<RoleOverrideEntry> entries;

    public EffectiveSnapshot(Map<ResourceLocation, ReplaceRoleDefinition> replaces,
                             Map<ResourceLocation, ModifyRoleDefinition> modifies) {
        this(replaces, modifies, List.of());
    }

    public EffectiveSnapshot(Map<ResourceLocation, ReplaceRoleDefinition> replaces,
                             Map<ResourceLocation, ModifyRoleDefinition> modifies,
                             List<RoleOverrideEntry> entries) {
        this.activeReplaces = Map.copyOf(replaces);
        this.activeModifies = Map.copyOf(modifies);
        this.entries = List.copyOf(entries);
    }

    public Map<ResourceLocation, ReplaceRoleDefinition> getActiveReplaces() { return activeReplaces; }
    public Map<ResourceLocation, ModifyRoleDefinition> getActiveModifies() { return activeModifies; }
    public List<RoleOverrideEntry> getEntries() { return entries; }
    public boolean isEmpty() { return activeReplaces.isEmpty() && activeModifies.isEmpty(); }
}
