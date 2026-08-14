package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.book.RoleBookContent;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleEconomyProfile;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RoleInventoryProfile;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleRelationProfile;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.api.role.v2.definition.RoleVisibilityProfile;
import com.habitrain.core.api.role.v2.skill.RoleSkillSpec;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole.MoodType;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Generic, data-driven {@code SRERole} produced by the v2 {@code ADD} model.
 *
 * <p>A {@code ManagedSRERole} is compiled from a declarative {@link RoleDefinition}
 * so ordinary providers no longer subclass {@code NormalRole} or override
 * getters. It stays a real upstream {@link NormalRole} underneath, so it remains
 * compatible with SRE logic, CCA component keys and the unified skill system.
 *
 * <p>{@link #from} is a pure construction: it never touches {@code TMMRoles}'s
 * static initializer and never resolves relation keys onto counterpart roles,
 * so it is unit-testable without a bootstrapped game.
 */
public class ManagedSRERole extends NormalRole {

    /** Explicit default items, or {@code null} to keep the upstream default. */
    private final @Nullable List<ItemStack> defaultItems;
    /** Explicit shop entries, or {@code null} to keep the upstream default. */
    private final @Nullable List<ShopEntry> shopEntries;
    /** Rebuilds the shop on every open when present. */
    private final @Nullable java.util.function.Supplier<List<ShopEntry>> shopLive;
    /** Unresolved relation keys; linked later by {@link RoleExtensionCompiler#linkRelations}. */
    private final @Nullable RoleRelationProfile relationProfile;
    private final List<RoleSkillSpec> skills;
    private final @Nullable RoleBookContent book;

    private ManagedSRERole(ResourceLocation identifier, int color, boolean isInnocent,
                           boolean canUseKiller, MoodType moodType, int maxSprintTime,
                           boolean canSeeTime,
                           @Nullable List<ItemStack> defaultItems,
                           @Nullable List<ShopEntry> shopEntries,
                           @Nullable java.util.function.Supplier<List<ShopEntry>> shopLive,
                           @Nullable RoleRelationProfile relationProfile,
                           List<RoleSkillSpec> skills,
                           @Nullable RoleBookContent book) {
        super(identifier, color, isInnocent, canUseKiller, moodType, maxSprintTime, canSeeTime);
        this.defaultItems = defaultItems;
        this.shopEntries = shopEntries;
        this.shopLive = shopLive;
        this.relationProfile = relationProfile;
        this.skills = List.copyOf(skills);
        this.book = book;
    }

    /**
     * Compiles a {@link RoleDefinition} into a {@code ManagedSRERole}, applying
     * every profile via the upstream fluent setters. Does not register the role
     * and does not resolve relation keys.
     */
    public static ManagedSRERole from(RoleDefinition def) {
        RolePresentation presentation = def.presentation();
        RoleFactionProfile faction = def.faction();
        RoleSpawnProfile spawn = def.spawn();
        RoleCompatibilityProfile compatibility = def.compatibility();
        RoleInventoryProfile inventory = def.inventory();
        RoleEconomyProfile economy = def.economy();
        RoleVisibilityProfile visibility = def.visibility();

        ManagedSRERole role = new ManagedSRERole(
                def.key().location(),
                presentation.color(),
                faction.innocent(),
                faction.canUseKiller(),
                presentation.moodType(),
                def.maxSprintTime(),
                def.canSeeTime(),
                inventory == null ? null : copyItems(inventory.defaultItems()),
                economy == null ? null : List.copyOf(economy.shopEntries()),
                economy == null ? null : economy.live(),
                def.relations(),
                def.skills(),
                def.book()
        );

        if (compatibility.componentKey() != null) {
            role.setComponentKey(compatibility.componentKey());
        }
        if (compatibility.canSeeCoin()) {
            role.setCanSeeCoin(true);
        }
        if (compatibility.canPickUpRevolver()) {
            role.setCanPickUpRevolver(true);
        }
        if (compatibility.canBeRandomed()) {
            role.setCanBeRandomedByOtherRoles(true);
        }
        if (compatibility.otherModeRole()) {
            role.setOtherModeRole(true);
        }
        if (compatibility.specialMapRole() != null
                && compatibility.specialMapRole() != io.wifi.starrailexpress.api.SRERole.SpecialMapRoleMap.ALL) {
            role.setSpecialMapRole(compatibility.specialMapRole());
        }
        if (compatibility.hiddenForRotation()) {
            role.setHiddenForRoleRotation(true);
        }
        if (compatibility.occupiedRoleCount() != 1) {
            role.setOccupiedRoleCount(compatibility.occupiedRoleCount());
        }

        if (faction.neutralExplicit()) {
            role.setNeutrals(faction.neutral());
        }
        if (faction.vigilanteTeamExplicit()) {
            role.setVigilanteTeam(faction.vigilanteTeam());
        }
        if (faction.neutralForKillerExplicit()) {
            role.setNeutralForKiller(faction.neutralForKiller());
        }
        if (faction.neutralForInnocentExplicit()) {
            role.setNeutralForInnocent(faction.neutralForInnocent());
        }
        if (faction.mafiaTeamExplicit()) {
            role.setMafiaTeam(faction.mafiaTeam());
        }

        if (visibility != null) {
            role.setCanUseInstinct(visibility.canUseInstinct());
            role.setInstinctNightVision(visibility.instinctNightVision());
            role.setCanSeeTeammateKillerRole(visibility.canSeeTeammateKiller());
        }

        role.setDefaultMax(spawn.defaultMaxCount());
        role.setDefaultEnableChance(spawn.defaultEnableChance());
        role.setDefaultEnableNeededPlayerCount(spawn.defaultEnableNeedPlayerCount());
        role.setDefaultEnableMaxPlayerCount(spawn.defaultEnableMaxPlayerCount());

        return role;
    }

    /** Unresolved relation keys captured from the definition, or {@code null}. */
    public @Nullable RoleRelationProfile relationProfile() {
        return relationProfile;
    }

    /** Occupation keys from the stored relation profile (empty when absent). */
    public List<RoleKey> occupationRoleKeys() {
        return relationProfile == null ? List.of() : relationProfile.occupation();
    }

    /** Opposing keys from the stored relation profile (empty when absent). */
    public List<RoleKey> opposingRoleKeys() {
        return relationProfile == null ? List.of() : relationProfile.opposing();
    }

    /** Related keys from the stored relation profile (empty when absent). */
    public List<RoleKey> relatedRoleKeys() {
        return relationProfile == null ? List.of() : relationProfile.related();
    }

    /** Skills declared on the ADD definition (empty when none). */
    public List<RoleSkillSpec> skills() {
        return skills;
    }

    /** Complete provider-owned book, or {@code null} to keep upstream tabs. */
    public @Nullable RoleBookContent book() {
        return book;
    }

    @Override
    public List<ItemStack> getDefaultItems() {
        return defaultItems != null ? copyItems(defaultItems) : super.getDefaultItems();
    }

    private static List<ItemStack> copyItems(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<ItemStack> out = new java.util.ArrayList<>(items.size());
        for (ItemStack item : items) {
            out.add(item == null || item.isEmpty() ? ItemStack.EMPTY : item.copy());
        }
        return List.copyOf(out);
    }

    @Override
    public List<ShopEntry> getShopEntries() {
        if (shopLive != null) {
            List<ShopEntry> fresh = shopLive.get();
            return fresh == null ? List.of() : fresh;
        }
        return shopEntries != null ? shopEntries : super.getShopEntries();
    }
}
