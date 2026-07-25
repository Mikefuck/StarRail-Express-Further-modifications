package com.habitrain.core.role.override;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.ReplaceRoleDefinition;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.Map;

public final class EffectiveSnapshot {
    private final Map<ResourceLocation, ReplaceRoleDefinition> activeReplaces;
    private final Map<ResourceLocation, ModifyRoleDefinition> activeModifies;

    public EffectiveSnapshot(Map<ResourceLocation, ReplaceRoleDefinition> replaces,
                             Map<ResourceLocation, ModifyRoleDefinition> modifies) {
        this.activeReplaces = Collections.unmodifiableMap(replaces);
        this.activeModifies = Collections.unmodifiableMap(modifies);
    }

    public Map<ResourceLocation, ReplaceRoleDefinition> getActiveReplaces() { return activeReplaces; }
    public Map<ResourceLocation, ModifyRoleDefinition> getActiveModifies() { return activeModifies; }
    public boolean isEmpty() { return activeReplaces.isEmpty() && activeModifies.isEmpty(); }
}
