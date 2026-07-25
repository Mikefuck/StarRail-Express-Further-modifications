package com.habitrain.core.api.role;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public final class ReplaceRoleDefinition {
    private final String sourceModId;
    private final Component displayName;
    private final Optional<Component> description;
    private final Optional<ResourceLocation> icon;
    private final Optional<String> customTypeLabel;
    private final ResourceLocation targetRoleId;
    private final SRERole replacementRole;
    private final Optional<ResourceLocation> replacementId;

    private ReplaceRoleDefinition(Builder b) {
        this.sourceModId = Objects.requireNonNull(b.sourceModId, "sourceModId");
        this.displayName = Objects.requireNonNull(b.displayName, "displayName");
        this.description = Optional.ofNullable(b.description);
        this.icon = Optional.ofNullable(b.icon);
        this.customTypeLabel = Optional.ofNullable(b.customTypeLabel);
        this.targetRoleId = Objects.requireNonNull(b.targetRoleId, "targetRoleId");
        this.replacementRole = Objects.requireNonNull(b.replacementRole, "replacementRole");
        this.replacementId = Optional.ofNullable(b.replacementId);
    }

    public static Builder builder() { return new Builder(); }

    public String sourceModId() { return sourceModId; }
    public RoleOverrideKind kind() { return RoleOverrideKind.REPLACE; }
    public Component displayName() { return displayName; }
    public Optional<Component> description() { return description; }
    public Optional<ResourceLocation> icon() { return icon; }
    public Optional<String> customTypeLabel() { return customTypeLabel; }
    public ResourceLocation targetRoleId() { return targetRoleId; }
    public SRERole replacementRole() { return replacementRole; }
    public Optional<ResourceLocation> replacementId() { return replacementId; }

    public static final class Builder {
        private String sourceModId;
        private Component displayName;
        private Component description;
        private ResourceLocation icon;
        private String customTypeLabel;
        private ResourceLocation targetRoleId;
        private SRERole replacementRole;
        private ResourceLocation replacementId;

        public Builder sourceModId(String v) { this.sourceModId = v; return this; }
        public Builder displayName(Component v) { this.displayName = v; return this; }
        public Builder description(Component v) { this.description = v; return this; }
        public Builder icon(ResourceLocation v) { this.icon = v; return this; }
        public Builder customTypeLabel(String v) { this.customTypeLabel = v; return this; }
        public Builder targetRoleId(ResourceLocation v) { this.targetRoleId = v; return this; }
        public Builder replacementRole(SRERole v) { this.replacementRole = v; return this; }
        public Builder replacementId(ResourceLocation v) { this.replacementId = v; return this; }

        public ReplaceRoleDefinition build() { return new ReplaceRoleDefinition(this); }
    }
}
