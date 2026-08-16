package com.habitrain.core.api.role;

import com.habitrain.core.api.role.patch.*;
import com.habitrain.core.api.role.book.RoleBookPage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class ModifyRoleDefinition {
    private final String sourceModId;
    private final Component displayName;
    private final Optional<Component> description;
    private final Optional<ResourceLocation> icon;
    private final Optional<String> customTypeLabel;
    private final Optional<String> entryKey;
    private final ResourceLocation targetRoleId;
    private final Optional<NamePatch> namePatch;
    private final Optional<ColorPatch> colorPatch;
    private final Optional<RoleTextPatch> descriptionPatch;
    private final Optional<RoleTextPatch> simpleDescriptionPatch;
    private final Optional<ShopPatch> shopPatch;
    private final Optional<ShopTransform> shopTransform;
    private final Optional<DefaultItemsPatch> defaultItemsPatch;
    private final Optional<FlagsPatch> flagsPatch;
    private final Optional<SpawnInfoPatch> spawnInfoPatch;
    private final Optional<SkillRegistrar> skillRegistrar;
    private final Optional<ManagedSkillPatch> managedSkillPatch;
    private final Optional<WinConditionHook> winConditionHook;
    private final List<RoleBookPage> roleBookAppendices;

    private ModifyRoleDefinition(Builder b) {
        this.sourceModId = Objects.requireNonNull(b.sourceModId, "sourceModId");
        this.displayName = Objects.requireNonNull(b.displayName, "displayName");
        this.description = Optional.ofNullable(b.description);
        this.icon = Optional.ofNullable(b.icon);
        this.customTypeLabel = Optional.ofNullable(b.customTypeLabel);
        this.entryKey = Optional.ofNullable(b.entryKey);
        this.targetRoleId = Objects.requireNonNull(b.targetRoleId, "targetRoleId");
        this.namePatch = Optional.ofNullable(b.namePatch);
        this.colorPatch = Optional.ofNullable(b.colorPatch);
        this.descriptionPatch = Optional.ofNullable(b.descriptionPatch);
        this.simpleDescriptionPatch = Optional.ofNullable(b.simpleDescriptionPatch);
        this.shopPatch = Optional.ofNullable(b.shopPatch);
        this.shopTransform = Optional.ofNullable(b.shopTransform);
        this.defaultItemsPatch = Optional.ofNullable(b.defaultItemsPatch);
        this.flagsPatch = Optional.ofNullable(b.flagsPatch);
        this.spawnInfoPatch = Optional.ofNullable(b.spawnInfoPatch);
        this.skillRegistrar = Optional.ofNullable(b.skillRegistrar);
        this.managedSkillPatch = Optional.ofNullable(b.managedSkillPatch);
        this.winConditionHook = Optional.ofNullable(b.winConditionHook);
        this.roleBookAppendices = List.copyOf(b.roleBookAppendices);
    }

    public static Builder builder() { return new Builder(); }

    public String sourceModId() { return sourceModId; }
    public RoleOverrideKind kind() { return RoleOverrideKind.MODIFY; }
    public Component displayName() { return displayName; }
    public Optional<Component> description() { return description; }
    public Optional<ResourceLocation> icon() { return icon; }
    public Optional<String> customTypeLabel() { return customTypeLabel; }
    public Optional<String> entryKey() { return entryKey; }
    public ResourceLocation targetRoleId() { return targetRoleId; }
    public Optional<NamePatch> namePatch() { return namePatch; }
    public Optional<ColorPatch> colorPatch() { return colorPatch; }
    public Optional<RoleTextPatch> descriptionPatch() { return descriptionPatch; }
    public Optional<RoleTextPatch> simpleDescriptionPatch() { return simpleDescriptionPatch; }
    public Optional<ShopPatch> shopPatch() { return shopPatch; }
    public Optional<ShopTransform> shopTransform() { return shopTransform; }
    public Optional<DefaultItemsPatch> defaultItemsPatch() { return defaultItemsPatch; }
    public Optional<FlagsPatch> flagsPatch() { return flagsPatch; }
    public Optional<SpawnInfoPatch> spawnInfoPatch() { return spawnInfoPatch; }
    /**
     * Legacy one-way registration callback. Prefer {@link #managedSkillPatch()} for new integrations.
     */
    @Deprecated
    public Optional<SkillRegistrar> skillRegistrar() { return skillRegistrar; }
    public Optional<ManagedSkillPatch> managedSkillPatch() { return managedSkillPatch; }
    public Optional<WinConditionHook> winConditionHook() { return winConditionHook; }
    /**
     * Provider-owned pages appended after all upstream role-book pages while
     * this MODIFY definition is active.
     */
    public List<RoleBookPage> roleBookAppendices() { return roleBookAppendices; }

    public static final class Builder {
        private String sourceModId;
        private Component displayName;
        private Component description;
        private ResourceLocation icon;
        private String customTypeLabel;
        private String entryKey;
        private ResourceLocation targetRoleId;
        private NamePatch namePatch;
        private ColorPatch colorPatch;
        private RoleTextPatch descriptionPatch;
        private RoleTextPatch simpleDescriptionPatch;
        private ShopPatch shopPatch;
        private ShopTransform shopTransform;
        private DefaultItemsPatch defaultItemsPatch;
        private FlagsPatch flagsPatch;
        private SpawnInfoPatch spawnInfoPatch;
        private SkillRegistrar skillRegistrar;
        private ManagedSkillPatch managedSkillPatch;
        private WinConditionHook winConditionHook;
        private final List<RoleBookPage> roleBookAppendices = new ArrayList<>();

        public Builder sourceModId(String v) { this.sourceModId = v; return this; }
        public Builder displayName(Component v) { this.displayName = v; return this; }
        public Builder description(Component v) { this.description = v; return this; }
        public Builder icon(ResourceLocation v) { this.icon = v; return this; }
        public Builder customTypeLabel(String v) { this.customTypeLabel = v; return this; }
        /**
         * Stable per-source key. Required when one mod declares more than one MODIFY
         * for the same target.
         */
        public Builder entryKey(String v) { this.entryKey = v; return this; }
        public Builder targetRoleId(ResourceLocation v) { this.targetRoleId = v; return this; }
        public Builder namePatch(NamePatch v) { this.namePatch = v; return this; }
        public Builder colorPatch(ColorPatch v) { this.colorPatch = v; return this; }
        public Builder descriptionPatch(RoleTextPatch v) { this.descriptionPatch = v; return this; }
        public Builder simpleDescriptionPatch(RoleTextPatch v) { this.simpleDescriptionPatch = v; return this; }
        public Builder shopPatch(ShopPatch v) { this.shopPatch = v; return this; }
        public Builder shopTransform(ShopTransform v) { this.shopTransform = v; return this; }
        public Builder defaultItemsPatch(DefaultItemsPatch v) { this.defaultItemsPatch = v; return this; }
        public Builder flagsPatch(FlagsPatch v) { this.flagsPatch = v; return this; }
        public Builder spawnInfoPatch(SpawnInfoPatch v) { this.spawnInfoPatch = v; return this; }
        /**
         * @deprecated One-way upstream registration cannot be safely undone. Use
         * {@link #managedSkillPatch(ManagedSkillPatch)}.
         */
        @Deprecated
        public Builder skillRegistrar(SkillRegistrar v) { this.skillRegistrar = v; return this; }
        public Builder managedSkillPatch(ManagedSkillPatch v) { this.managedSkillPatch = v; return this; }
        public Builder winConditionHook(WinConditionHook v) { this.winConditionHook = v; return this; }
        public Builder roleBookAppendix(RoleBookPage page) {
            this.roleBookAppendices.add(Objects.requireNonNull(page, "page"));
            return this;
        }
        public Builder roleBookAppendices(Collection<RoleBookPage> pages) {
            Objects.requireNonNull(pages, "pages").forEach(this::roleBookAppendix);
            return this;
        }

        public ModifyRoleDefinition build() { return new ModifyRoleDefinition(this); }
    }
}
