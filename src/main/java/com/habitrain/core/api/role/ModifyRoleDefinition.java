package com.habitrain.core.api.role;

import com.habitrain.core.api.role.patch.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public final class ModifyRoleDefinition {
    private final String sourceModId;
    private final Component displayName;
    private final Optional<Component> description;
    private final Optional<ResourceLocation> icon;
    private final Optional<String> customTypeLabel;
    private final ResourceLocation targetRoleId;
    private final Optional<NamePatch> namePatch;
    private final Optional<ColorPatch> colorPatch;
    private final Optional<ShopPatch> shopPatch;
    private final Optional<DefaultItemsPatch> defaultItemsPatch;
    private final Optional<FlagsPatch> flagsPatch;
    private final Optional<SpawnInfoPatch> spawnInfoPatch;
    private final Optional<SkillRegistrar> skillRegistrar;
    private final Optional<WinConditionHook> winConditionHook;

    private ModifyRoleDefinition(Builder b) {
        this.sourceModId = Objects.requireNonNull(b.sourceModId, "sourceModId");
        this.displayName = Objects.requireNonNull(b.displayName, "displayName");
        this.description = Optional.ofNullable(b.description);
        this.icon = Optional.ofNullable(b.icon);
        this.customTypeLabel = Optional.ofNullable(b.customTypeLabel);
        this.targetRoleId = Objects.requireNonNull(b.targetRoleId, "targetRoleId");
        this.namePatch = Optional.ofNullable(b.namePatch);
        this.colorPatch = Optional.ofNullable(b.colorPatch);
        this.shopPatch = Optional.ofNullable(b.shopPatch);
        this.defaultItemsPatch = Optional.ofNullable(b.defaultItemsPatch);
        this.flagsPatch = Optional.ofNullable(b.flagsPatch);
        this.spawnInfoPatch = Optional.ofNullable(b.spawnInfoPatch);
        this.skillRegistrar = Optional.ofNullable(b.skillRegistrar);
        this.winConditionHook = Optional.ofNullable(b.winConditionHook);
    }

    public static Builder builder() { return new Builder(); }

    public String sourceModId() { return sourceModId; }
    public RoleOverrideKind kind() { return RoleOverrideKind.MODIFY; }
    public Component displayName() { return displayName; }
    public Optional<Component> description() { return description; }
    public Optional<ResourceLocation> icon() { return icon; }
    public Optional<String> customTypeLabel() { return customTypeLabel; }
    public ResourceLocation targetRoleId() { return targetRoleId; }
    public Optional<NamePatch> namePatch() { return namePatch; }
    public Optional<ColorPatch> colorPatch() { return colorPatch; }
    public Optional<ShopPatch> shopPatch() { return shopPatch; }
    public Optional<DefaultItemsPatch> defaultItemsPatch() { return defaultItemsPatch; }
    public Optional<FlagsPatch> flagsPatch() { return flagsPatch; }
    public Optional<SpawnInfoPatch> spawnInfoPatch() { return spawnInfoPatch; }
    public Optional<SkillRegistrar> skillRegistrar() { return skillRegistrar; }
    public Optional<WinConditionHook> winConditionHook() { return winConditionHook; }

    public static final class Builder {
        private String sourceModId;
        private Component displayName;
        private Component description;
        private ResourceLocation icon;
        private String customTypeLabel;
        private ResourceLocation targetRoleId;
        private NamePatch namePatch;
        private ColorPatch colorPatch;
        private ShopPatch shopPatch;
        private DefaultItemsPatch defaultItemsPatch;
        private FlagsPatch flagsPatch;
        private SpawnInfoPatch spawnInfoPatch;
        private SkillRegistrar skillRegistrar;
        private WinConditionHook winConditionHook;

        public Builder sourceModId(String v) { this.sourceModId = v; return this; }
        public Builder displayName(Component v) { this.displayName = v; return this; }
        public Builder description(Component v) { this.description = v; return this; }
        public Builder icon(ResourceLocation v) { this.icon = v; return this; }
        public Builder customTypeLabel(String v) { this.customTypeLabel = v; return this; }
        public Builder targetRoleId(ResourceLocation v) { this.targetRoleId = v; return this; }
        public Builder namePatch(NamePatch v) { this.namePatch = v; return this; }
        public Builder colorPatch(ColorPatch v) { this.colorPatch = v; return this; }
        public Builder shopPatch(ShopPatch v) { this.shopPatch = v; return this; }
        public Builder defaultItemsPatch(DefaultItemsPatch v) { this.defaultItemsPatch = v; return this; }
        public Builder flagsPatch(FlagsPatch v) { this.flagsPatch = v; return this; }
        public Builder spawnInfoPatch(SpawnInfoPatch v) { this.spawnInfoPatch = v; return this; }
        public Builder skillRegistrar(SkillRegistrar v) { this.skillRegistrar = v; return this; }
        public Builder winConditionHook(WinConditionHook v) { this.winConditionHook = v; return this; }

        public ModifyRoleDefinition build() { return new ModifyRoleDefinition(this); }
    }
}
