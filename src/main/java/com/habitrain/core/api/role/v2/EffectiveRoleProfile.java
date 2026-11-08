package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.patch.DefaultItemsPatch;
import com.habitrain.core.api.role.v2.patch.FlagsPatch;
import com.habitrain.core.api.role.v2.patch.NamePatch;
import com.habitrain.core.api.role.v2.patch.RoleTextPatch;
import com.habitrain.core.api.role.v2.patch.ShopPatch;
import com.habitrain.core.api.role.v2.patch.ShopTransform;
import com.habitrain.core.api.role.v2.patch.SpawnInfoPatch;
import com.habitrain.core.api.role.v2.patch.WinConditionHook;
import com.habitrain.core.api.role.v2.skill.RoleSkillSpec;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.SRERole.MoodType;
import io.wifi.starrailexpress.api.SRERole.SpecialMapRoleMap;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, serializable-in-principle description of an effective role.
 *
 * <p>Snapshots and their archive retain this value object rather than a live
 * {@link SRERole}.  A current snapshot may separately expose a runtime handle
 * for compatibility with the upstream API, but historical presentation and
 * gameplay metadata always come from this frozen profile.
 * Prefer {@link #builder} or {@link #toBuilder()} for named fields; positional
 * constructors remain available for existing binary callers.
 */
public record EffectiveRoleProfile(
        RoleKey key,
        EffectiveRole.Source source,
        int color,
        MoodType mood,
        boolean innocent,
        boolean canUseKiller,
        boolean neutral,
        boolean vigilanteTeam,
        int defaultMax,
        int enableChance,
        int needPlayerCount,
        int maxPlayerCount,
        boolean canSeeCoin,
        boolean canPickUpRevolver,
        boolean canBeRandomed,
        int maxSprintTime,
        boolean canSeeTime,
        boolean neutralForKiller,
        boolean neutralForInnocent,
        boolean mafiaTeam,
        boolean canUseInstinct,
        boolean instinctNightVision,
        boolean canSeeTeammateKiller,
        boolean otherModeRole,
        boolean hiddenForRotation,
        int occupiedRoleCount,
        SpecialMapRoleMap specialMapRole,
        List<RoleKey> occupationKeys,
        List<RoleKey> opposingKeys,
        List<RoleKey> relatedKeys,
        /** Direction of the opposing relation wired from the opposing keys; see
         *  {@link com.habitrain.core.api.role.v2.definition.RoleRelationProfile#opposingTwoWay()}.
         *  Round-tripped by {@link #withOverlay}/{@link #toOverlay} so a rebuilt
         *  overlay no longer hard-codes two-way (review M15). */
        boolean opposingTwoWay,
        List<RoleSkillSpec> skills,
        boolean skillsSpecified,
        NamePatch namePatch,
        RoleTextPatch descriptionPatch,
        RoleTextPatch simpleDescriptionPatch,
        DefaultItemsPatch defaultItemsPatch,
        ShopPatch shopPatch,
        ShopTransform shopTransform,
        WinConditionHook winConditionHook,
        com.habitrain.core.api.role.v2.patch.ColorPatch colorProvider,
        FlagsPatch flagsPatch,
        SpawnInfoPatch spawnInfoPatch,
        java.util.Set<String> flags) {

    public EffectiveRoleProfile {
        flags = java.util.Set.copyOf(Objects.requireNonNullElse(flags, java.util.Set.of()));
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mood, "mood");
        Objects.requireNonNull(specialMapRole, "specialMapRole");
        occupationKeys = List.copyOf(Objects.requireNonNullElse(occupationKeys, List.of()));
        opposingKeys = List.copyOf(Objects.requireNonNullElse(opposingKeys, List.of()));
        relatedKeys = List.copyOf(Objects.requireNonNullElse(relatedKeys, List.of()));
        skills = List.copyOf(Objects.requireNonNullElse(skills, List.of()));
    }

    /**
     * Compat constructor for the pre-{@code opposingTwoWay} signature; keeps
     * source compatibility for existing positional callers and defaults the
     * opposing relation to two-way.
     */
    public EffectiveRoleProfile(
            RoleKey key, EffectiveRole.Source source, int color, MoodType mood,
            boolean innocent, boolean canUseKiller, boolean neutral, boolean vigilanteTeam,
            int defaultMax, int enableChance, int needPlayerCount, int maxPlayerCount,
            boolean canSeeCoin, boolean canPickUpRevolver, boolean canBeRandomed,
            int maxSprintTime, boolean canSeeTime, boolean neutralForKiller, boolean neutralForInnocent,
            boolean mafiaTeam, boolean canUseInstinct, boolean instinctNightVision,
            boolean canSeeTeammateKiller, boolean otherModeRole, boolean hiddenForRotation,
            int occupiedRoleCount, SpecialMapRoleMap specialMapRole,
            List<RoleKey> occupationKeys, List<RoleKey> opposingKeys, List<RoleKey> relatedKeys,
            List<RoleSkillSpec> skills, boolean skillsSpecified,
            NamePatch namePatch, RoleTextPatch descriptionPatch, RoleTextPatch simpleDescriptionPatch,
            DefaultItemsPatch defaultItemsPatch, ShopPatch shopPatch, ShopTransform shopTransform,
            WinConditionHook winConditionHook,
            com.habitrain.core.api.role.v2.patch.ColorPatch colorProvider,
            FlagsPatch flagsPatch, SpawnInfoPatch spawnInfoPatch) {
        this(key, source, color, mood, innocent, canUseKiller, neutral, vigilanteTeam,
                defaultMax, enableChance, needPlayerCount, maxPlayerCount,
                canSeeCoin, canPickUpRevolver, canBeRandomed,
                maxSprintTime, canSeeTime, neutralForKiller, neutralForInnocent,
                mafiaTeam, canUseInstinct, instinctNightVision,
                canSeeTeammateKiller, otherModeRole, hiddenForRotation,
                occupiedRoleCount, specialMapRole,
                occupationKeys, opposingKeys, relatedKeys, true, skills, skillsSpecified,
                namePatch, descriptionPatch, simpleDescriptionPatch,
                defaultItemsPatch, shopPatch, shopTransform, winConditionHook,
                colorProvider, flagsPatch, spawnInfoPatch);
    }

    /** Compatibility constructor for profiles created before frozen flags were captured. */
    public EffectiveRoleProfile(
            RoleKey key,
            EffectiveRole.Source source,
            int color,
            MoodType mood,
            boolean innocent,
            boolean canUseKiller,
            boolean neutral,
            boolean vigilanteTeam,
            int defaultMax,
            int enableChance,
            int needPlayerCount,
            int maxPlayerCount,
            boolean canSeeCoin,
            boolean canPickUpRevolver,
            boolean canBeRandomed,
            int maxSprintTime,
            boolean canSeeTime,
            boolean neutralForKiller,
            boolean neutralForInnocent,
            boolean mafiaTeam,
            boolean canUseInstinct,
            boolean instinctNightVision,
            boolean canSeeTeammateKiller,
            boolean otherModeRole,
            boolean hiddenForRotation,
            int occupiedRoleCount,
            SpecialMapRoleMap specialMapRole,
            List<RoleKey> occupationKeys,
            List<RoleKey> opposingKeys,
            List<RoleKey> relatedKeys,
            boolean opposingTwoWay,
            List<RoleSkillSpec> skills,
            boolean skillsSpecified,
            NamePatch namePatch,
            RoleTextPatch descriptionPatch,
            RoleTextPatch simpleDescriptionPatch,
            DefaultItemsPatch defaultItemsPatch,
            ShopPatch shopPatch,
            ShopTransform shopTransform,
            WinConditionHook winConditionHook,
            com.habitrain.core.api.role.v2.patch.ColorPatch colorProvider,
            FlagsPatch flagsPatch,
            SpawnInfoPatch spawnInfoPatch) {
        this(key, source, color, mood, innocent, canUseKiller, neutral, vigilanteTeam, defaultMax, enableChance, needPlayerCount, maxPlayerCount, canSeeCoin, canPickUpRevolver, canBeRandomed, maxSprintTime, canSeeTime, neutralForKiller, neutralForInnocent, mafiaTeam, canUseInstinct, instinctNightVision, canSeeTeammateKiller, otherModeRole, hiddenForRotation, occupiedRoleCount, specialMapRole, occupationKeys, opposingKeys, relatedKeys, opposingTwoWay, skills, skillsSpecified, namePatch, descriptionPatch, simpleDescriptionPatch, defaultItemsPatch, shopPatch, shopTransform, winConditionHook, colorProvider, flagsPatch, spawnInfoPatch, java.util.Set.of());
    }

    /** Captures the baseline values exposed by the real upstream role. */
    public static EffectiveRoleProfile from(RoleKey key, SRERole role, EffectiveRole.Source source) {
        Objects.requireNonNull(role, "role");
        boolean twoWay = role.opposingRoles == null || role.opposingRoles.isEmpty()
                || role.opposingRoles.stream()
                        .filter(Objects::nonNull)
                        .allMatch(other -> other.opposingRoles != null && other.opposingRoles.contains(role));
        return from(key, role, source, twoWay, List.of(), false);
    }

    /** Captures the baseline values with explicit relation direction and skill specs (ROLE-API-24). */
    public static EffectiveRoleProfile from(RoleKey key, SRERole role, EffectiveRole.Source source,
                                            boolean opposingTwoWay, List<RoleSkillSpec> skills,
                                            boolean skillsSpecified) {
        Objects.requireNonNull(role, "role");
        return EffectiveRoleProfile.builder(key, source, role.getMoodType(), role.getSpecialMapRole())
                .color(role.getColor())
                .innocent(role.isInnocent())
                .canUseKiller(role.canUseKiller())
                .neutral(role.isNeutrals())
                .vigilanteTeam(role.isVigilanteTeam())
                .defaultMax(role.defaultMaxCount)
                .enableChance(role.defaultEnableChance)
                .needPlayerCount(role.defaultEnableNeedPlayerCount)
                .maxPlayerCount(role.defaultEnableMaxPlayerCount)
                .canSeeCoin(role.canSeeCoin())
                .canPickUpRevolver(role.canPickUpRevolver())
                .canBeRandomed(role.canBeRandomedDefination())
                .maxSprintTime(role.getMaxSprintTime())
                .canSeeTime(role.canSeeTime())
                .neutralForKiller(role.isNeutralForKiller())
                .neutralForInnocent(role.isNeutralForInnocent())
                .mafiaTeam(role.isMafiaTeam())
                .canUseInstinct(role.canUseInstinct())
                .instinctNightVision(role.haveInstinctNightVision())
                .canSeeTeammateKiller(role.canSeeTeammateKillerRole())
                .otherModeRole(role.isOtherModeRole())
                .hiddenForRotation(role.getFlags().contains("inner.role_rotation.hidden"))
                .occupiedRoleCount(role.getOccupiedRoleCount())
                .occupationKeys(relationKeys(role.occupationRoles))
                .opposingKeys(relationKeys(role.opposingRoles))
                .relatedKeys(relationKeys(role.relatedRoles))
                .opposingTwoWay(opposingTwoWay)
                .skills(skills == null ? List.of() : List.copyOf(skills))
                .skillsSpecified(skillsSpecified)
                .namePatch(null)
                .descriptionPatch(null)
                .simpleDescriptionPatch(null)
                .defaultItemsPatch(null)
                .shopPatch(null)
                .shopTransform(null)
                .winConditionHook(null)
                .colorProvider(null)
                .flagsPatch(null)
                .spawnInfoPatch(null)
                .flags(java.util.Set.copyOf(role.getFlags()))
                .build();
    }

    /** Replaces all mutable gameplay fields with a compiled MODIFY result. */
    public EffectiveRoleProfile withOverlay(CompiledModifyOverlay overlay) {
        if (overlay == null) {
            return this;
        }
        return EffectiveRoleProfile.builder(key, source, overlay.mood(), overlay.specialMapRole())
                .color(overlay.color())
                .innocent(overlay.innocent())
                .canUseKiller(overlay.canUseKiller())
                .neutral(overlay.neutral())
                .vigilanteTeam(overlay.vigilanteTeam())
                .defaultMax(overlay.defaultMax())
                .enableChance(overlay.enableChance())
                .needPlayerCount(overlay.needPlayerCount())
                .maxPlayerCount(overlay.maxPlayerCount())
                .canSeeCoin(overlay.canSeeCoin())
                .canPickUpRevolver(overlay.canPickUpRevolver())
                .canBeRandomed(overlay.canBeRandomed())
                .maxSprintTime(overlay.maxSprintTime())
                .canSeeTime(overlay.canSeeTime())
                .neutralForKiller(overlay.neutralForKiller())
                .neutralForInnocent(overlay.neutralForInnocent())
                .mafiaTeam(overlay.mafiaTeam())
                .canUseInstinct(overlay.canUseInstinct())
                .instinctNightVision(overlay.instinctNightVision())
                .canSeeTeammateKiller(overlay.canSeeTeammateKiller())
                .otherModeRole(overlay.otherModeRole())
                .hiddenForRotation(overlay.hiddenForRotation())
                .occupiedRoleCount(overlay.occupiedRoleCount())
                .occupationKeys(overlay.occupationKeys())
                .opposingKeys(overlay.opposingKeys())
                .relatedKeys(overlay.relatedKeys())
                .opposingTwoWay(overlay.opposingTwoWay())
                .skills(overlay.skills())
                .skillsSpecified(overlay.skillsSpecified())
                .namePatch(overlay.namePatch())
                .descriptionPatch(overlay.descriptionPatch())
                .simpleDescriptionPatch(overlay.simpleDescriptionPatch())
                .defaultItemsPatch(overlay.defaultItemsPatch())
                .shopPatch(overlay.shopPatch())
                .shopTransform(overlay.shopTransform())
                .winConditionHook(overlay.winConditionHook())
                .colorProvider(overlay.colorProvider())
                .flagsPatch(overlay.flagsPatch())
                .spawnInfoPatch(overlay.spawnInfoPatch())
                .flags(flagsWithRotationVisibility(overlay.hiddenForRotation()))
                .build();
    }

    private java.util.Set<String> flagsWithRotationVisibility(boolean hidden) {
        java.util.Set<String> copy = new java.util.HashSet<>(flags);
        if (hidden) copy.add("inner.role_rotation.hidden");
        else copy.remove("inner.role_rotation.hidden");
        return java.util.Set.copyOf(copy);
    }

    /** Rebuilds a runtime overlay without consulting a live registry or config. */
    public CompiledModifyOverlay toOverlay() {
        return CompiledModifyOverlay.builder(mood, specialMapRole)
                .color(color)
                .colorProvider(colorProvider)
                .flagsPatch(flagsPatch)
                .spawnInfoPatch(spawnInfoPatch)
                .namePatch(namePatch)
                .descriptionPatch(descriptionPatch)
                .simpleDescriptionPatch(simpleDescriptionPatch)
                .defaultItemsPatch(defaultItemsPatch)
                .shopPatch(shopPatch)
                .shopTransform(shopTransform)
                .winConditionHook(winConditionHook)
                .innocent(innocent)
                .canUseKiller(canUseKiller)
                .neutral(neutral)
                .vigilanteTeam(vigilanteTeam)
                .defaultMax(defaultMax)
                .enableChance(enableChance)
                .needPlayerCount(needPlayerCount)
                .maxPlayerCount(maxPlayerCount)
                .canSeeCoin(canSeeCoin)
                .canPickUpRevolver(canPickUpRevolver)
                .canBeRandomed(canBeRandomed)
                .maxSprintTime(maxSprintTime)
                .canSeeTime(canSeeTime)
                .neutralForKiller(neutralForKiller)
                .neutralForInnocent(neutralForInnocent)
                .mafiaTeam(mafiaTeam)
                .canUseInstinct(canUseInstinct)
                .instinctNightVision(instinctNightVision)
                .canSeeTeammateKiller(canSeeTeammateKiller)
                .otherModeRole(otherModeRole)
                .hiddenForRotation(hiddenForRotation)
                .occupiedRoleCount(occupiedRoleCount)
                .occupationKeys(occupationKeys)
                .opposingKeys(opposingKeys)
                .relatedKeys(relatedKeys)
                .opposingTwoWay(opposingTwoWay)
                .skills(skills)
                .skillsSpecified(skillsSpecified)
                .build();
    }

    private static List<RoleKey> relationKeys(java.util.Collection<SRERole> roles) {
        return roles == null ? List.of() : roles.stream()
                .filter(Objects::nonNull).map(SRERole::identifier)
                .filter(Objects::nonNull).map(RoleKey::of).toList();
    }

    /**
     * Named construction. Unset primitives are zero/false and collections empty;
     * use {@code from(...).toBuilder()} to start from upstream role values.
     */
    public static Builder builder(RoleKey key, EffectiveRole.Source source, MoodType mood, SpecialMapRoleMap specialMapRole) {
        return new Builder(key, source, mood, specialMapRole);
    }

    /** Copies every field, including explicit empty lists and callback handles. */
    public Builder toBuilder() {
        return builder(key, source, mood, specialMapRole)
                .color(color)
                .innocent(innocent)
                .canUseKiller(canUseKiller)
                .neutral(neutral)
                .vigilanteTeam(vigilanteTeam)
                .defaultMax(defaultMax)
                .enableChance(enableChance)
                .needPlayerCount(needPlayerCount)
                .maxPlayerCount(maxPlayerCount)
                .canSeeCoin(canSeeCoin)
                .canPickUpRevolver(canPickUpRevolver)
                .canBeRandomed(canBeRandomed)
                .maxSprintTime(maxSprintTime)
                .canSeeTime(canSeeTime)
                .neutralForKiller(neutralForKiller)
                .neutralForInnocent(neutralForInnocent)
                .mafiaTeam(mafiaTeam)
                .canUseInstinct(canUseInstinct)
                .instinctNightVision(instinctNightVision)
                .canSeeTeammateKiller(canSeeTeammateKiller)
                .otherModeRole(otherModeRole)
                .hiddenForRotation(hiddenForRotation)
                .occupiedRoleCount(occupiedRoleCount)
                .occupationKeys(occupationKeys)
                .opposingKeys(opposingKeys)
                .relatedKeys(relatedKeys)
                .opposingTwoWay(opposingTwoWay)
                .skills(skills)
                .skillsSpecified(skillsSpecified)
                .namePatch(namePatch)
                .descriptionPatch(descriptionPatch)
                .simpleDescriptionPatch(simpleDescriptionPatch)
                .defaultItemsPatch(defaultItemsPatch)
                .shopPatch(shopPatch)
                .shopTransform(shopTransform)
                .winConditionHook(winConditionHook)
                .colorProvider(colorProvider)
                .flagsPatch(flagsPatch)
                .spawnInfoPatch(spawnInfoPatch)
                .flags(flags);
    }

    /** Mutable named builder. Only build() publishes the immutable value. */
    public static final class Builder {
        private RoleKey key;
        private EffectiveRole.Source source;
        private int color;
        private MoodType mood;
        private boolean innocent;
        private boolean canUseKiller;
        private boolean neutral;
        private boolean vigilanteTeam;
        private int defaultMax;
        private int enableChance;
        private int needPlayerCount;
        private int maxPlayerCount;
        private boolean canSeeCoin;
        private boolean canPickUpRevolver;
        private boolean canBeRandomed;
        private int maxSprintTime;
        private boolean canSeeTime;
        private boolean neutralForKiller;
        private boolean neutralForInnocent;
        private boolean mafiaTeam;
        private boolean canUseInstinct;
        private boolean instinctNightVision;
        private boolean canSeeTeammateKiller;
        private boolean otherModeRole;
        private boolean hiddenForRotation;
        private int occupiedRoleCount;
        private SpecialMapRoleMap specialMapRole;
        private List<RoleKey> occupationKeys = List.of();
        private List<RoleKey> opposingKeys = List.of();
        private List<RoleKey> relatedKeys = List.of();
        private boolean opposingTwoWay;
        private List<RoleSkillSpec> skills = List.of();
        private boolean skillsSpecified;
        private NamePatch namePatch;
        private RoleTextPatch descriptionPatch;
        private RoleTextPatch simpleDescriptionPatch;
        private DefaultItemsPatch defaultItemsPatch;
        private ShopPatch shopPatch;
        private ShopTransform shopTransform;
        private WinConditionHook winConditionHook;
        private com.habitrain.core.api.role.v2.patch.ColorPatch colorProvider;
        private FlagsPatch flagsPatch;
        private SpawnInfoPatch spawnInfoPatch;
        private java.util.Set<String> flags = java.util.Set.of();

        private Builder(RoleKey key, EffectiveRole.Source source, MoodType mood, SpecialMapRoleMap specialMapRole) {
            this.key = Objects.requireNonNull(key, "key");
            this.source = Objects.requireNonNull(source, "source");
            this.mood = Objects.requireNonNull(mood, "mood");
            this.specialMapRole = Objects.requireNonNull(specialMapRole, "specialMapRole");
        }

        public Builder color(int value) { this.color = value; return this; }
        public Builder innocent(boolean value) { this.innocent = value; return this; }
        public Builder canUseKiller(boolean value) { this.canUseKiller = value; return this; }
        public Builder neutral(boolean value) { this.neutral = value; return this; }
        public Builder vigilanteTeam(boolean value) { this.vigilanteTeam = value; return this; }
        public Builder defaultMax(int value) { this.defaultMax = value; return this; }
        public Builder enableChance(int value) { this.enableChance = value; return this; }
        public Builder needPlayerCount(int value) { this.needPlayerCount = value; return this; }
        public Builder maxPlayerCount(int value) { this.maxPlayerCount = value; return this; }
        public Builder canSeeCoin(boolean value) { this.canSeeCoin = value; return this; }
        public Builder canPickUpRevolver(boolean value) { this.canPickUpRevolver = value; return this; }
        public Builder canBeRandomed(boolean value) { this.canBeRandomed = value; return this; }
        public Builder maxSprintTime(int value) { this.maxSprintTime = value; return this; }
        public Builder canSeeTime(boolean value) { this.canSeeTime = value; return this; }
        public Builder neutralForKiller(boolean value) { this.neutralForKiller = value; return this; }
        public Builder neutralForInnocent(boolean value) { this.neutralForInnocent = value; return this; }
        public Builder mafiaTeam(boolean value) { this.mafiaTeam = value; return this; }
        public Builder canUseInstinct(boolean value) { this.canUseInstinct = value; return this; }
        public Builder instinctNightVision(boolean value) { this.instinctNightVision = value; return this; }
        public Builder canSeeTeammateKiller(boolean value) { this.canSeeTeammateKiller = value; return this; }
        public Builder otherModeRole(boolean value) { this.otherModeRole = value; return this; }
        public Builder hiddenForRotation(boolean value) { this.hiddenForRotation = value; return this; }
        public Builder occupiedRoleCount(int value) { this.occupiedRoleCount = value; return this; }
        public Builder occupationKeys(List<RoleKey> value) { this.occupationKeys = value; return this; }
        public Builder opposingKeys(List<RoleKey> value) { this.opposingKeys = value; return this; }
        public Builder relatedKeys(List<RoleKey> value) { this.relatedKeys = value; return this; }
        public Builder opposingTwoWay(boolean value) { this.opposingTwoWay = value; return this; }
        public Builder skills(List<RoleSkillSpec> value) { this.skills = value; return this; }
        public Builder skillsSpecified(boolean value) { this.skillsSpecified = value; return this; }
        public Builder namePatch(NamePatch value) { this.namePatch = value; return this; }
        public Builder descriptionPatch(RoleTextPatch value) { this.descriptionPatch = value; return this; }
        public Builder simpleDescriptionPatch(RoleTextPatch value) { this.simpleDescriptionPatch = value; return this; }
        public Builder defaultItemsPatch(DefaultItemsPatch value) { this.defaultItemsPatch = value; return this; }
        public Builder shopPatch(ShopPatch value) { this.shopPatch = value; return this; }
        public Builder shopTransform(ShopTransform value) { this.shopTransform = value; return this; }
        public Builder winConditionHook(WinConditionHook value) { this.winConditionHook = value; return this; }
        public Builder colorProvider(com.habitrain.core.api.role.v2.patch.ColorPatch value) { this.colorProvider = value; return this; }
        public Builder flagsPatch(FlagsPatch value) { this.flagsPatch = value; return this; }
        public Builder spawnInfoPatch(SpawnInfoPatch value) { this.spawnInfoPatch = value; return this; }
        public Builder flags(java.util.Set<String> value) { this.flags = value; return this; }

        public EffectiveRoleProfile build() {
            return new EffectiveRoleProfile(
                    key, source, color, mood,
                    innocent, canUseKiller, neutral, vigilanteTeam,
                    defaultMax, enableChance, needPlayerCount, maxPlayerCount,
                    canSeeCoin, canPickUpRevolver, canBeRandomed, maxSprintTime,
                    canSeeTime, neutralForKiller, neutralForInnocent, mafiaTeam,
                    canUseInstinct, instinctNightVision, canSeeTeammateKiller, otherModeRole,
                    hiddenForRotation, occupiedRoleCount, specialMapRole, occupationKeys,
                    opposingKeys, relatedKeys, opposingTwoWay, skills,
                    skillsSpecified, namePatch, descriptionPatch, simpleDescriptionPatch,
                    defaultItemsPatch, shopPatch, shopTransform, winConditionHook,
                    colorProvider, flagsPatch, spawnInfoPatch, flags);
        }
    }
}
