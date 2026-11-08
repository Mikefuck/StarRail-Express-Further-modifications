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
import io.wifi.starrailexpress.api.SRERole.MoodType;
import io.wifi.starrailexpress.api.SRERole.SpecialMapRoleMap;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Pure-data result of folding the v2 {@code MODIFY} patches for one role
 * (fix-doc §5.1). It carries the effective field values, relation keys and skill
 * list without replacing the underlying {@code SRERole} object, so a modified
 * role keeps its identity, component key and subclass behavior.
 *
 * <p>This value object is part of the public API: it is the precompiled overlay
 * exposed by {@link EffectiveRole#modifyOverlay()} and rebuilt by
 * {@link EffectiveRoleProfile#toOverlay()}. It is produced by the snapshot
 * compiler and materialized onto the original role only at snapshot activation
 * by the runtime overlay applier, keeping compilation pure.
 * Prefer {@link #builder} or {@link #toBuilder()} to the retained positional
 * constructors when assembling or editing an overlay.
 */
public final class CompiledModifyOverlay {

    private final int color;
    private final @Nullable com.habitrain.core.api.role.v2.patch.ColorPatch colorProvider;
    private final @Nullable FlagsPatch flagsPatch;
    private final @Nullable SpawnInfoPatch spawnInfoPatch;
    private final MoodType mood;
    private final @Nullable NamePatch namePatch;
    private final @Nullable RoleTextPatch descriptionPatch;
    private final @Nullable RoleTextPatch simpleDescriptionPatch;
    private final @Nullable DefaultItemsPatch defaultItemsPatch;
    private final @Nullable ShopPatch shopPatch;
    private final @Nullable ShopTransform shopTransform;
    private final @Nullable WinConditionHook winConditionHook;
    private final boolean innocent;
    private final boolean canUseKiller;
    private final boolean neutral;
    private final boolean vigilanteTeam;
    private final int defaultMax;
    private final int enableChance;
    private final int needPlayerCount;
    private final int maxPlayerCount;
    private final boolean canSeeCoin;
    private final boolean canPickUpRevolver;
    private final boolean canBeRandomed;
    private final int maxSprintTime;
    private final boolean canSeeTime;
    private final boolean neutralForKiller;
    private final boolean neutralForInnocent;
    private final boolean mafiaTeam;
    private final boolean canUseInstinct;
    private final boolean instinctNightVision;
    private final boolean canSeeTeammateKiller;
    private final boolean otherModeRole;
    private final boolean hiddenForRotation;
    private final int occupiedRoleCount;
    private final SpecialMapRoleMap specialMapRole;
    private final List<RoleKey> occupationKeys;
    private final List<RoleKey> opposingKeys;
    private final List<RoleKey> relatedKeys;
    private final boolean opposingTwoWay;
    private final List<RoleSkillSpec> skills;
    /** True when at least one patch explicitly supplied a skill operation. */
    private final boolean skillsSpecified;

    /**
     * Convenience constructor. {@code skillsSpecified} is derived from
     * {@code !skills.isEmpty()} so a non-empty list is never silently dropped by
     * {@link #hasSkills()}-dispatching consumers (review M13). The opposing
     * relation is wired two-way; use the full constructor for one-way
     * opposition.
     */
    public CompiledModifyOverlay(
            int color, MoodType mood, boolean innocent, boolean canUseKiller, boolean neutral,
            boolean vigilanteTeam, int defaultMax, int enableChance, int needPlayerCount,
            int maxPlayerCount, boolean canSeeCoin, boolean canPickUpRevolver, boolean canBeRandomed,
            int maxSprintTime, boolean canSeeTime, boolean neutralForKiller, boolean neutralForInnocent,
            boolean mafiaTeam, boolean canUseInstinct, boolean instinctNightVision,
            boolean canSeeTeammateKiller, boolean otherModeRole, boolean hiddenForRotation,
            int occupiedRoleCount, SpecialMapRoleMap specialMapRole,
            List<RoleKey> occupationKeys, List<RoleKey> opposingKeys, List<RoleKey> relatedKeys,
            List<RoleSkillSpec> skills) {
        this(color, mood,
                null, null, null,
                null, null, null, null, null, null, null,
                innocent, canUseKiller, neutral, vigilanteTeam,
                defaultMax, enableChance, needPlayerCount, maxPlayerCount,
                canSeeCoin, canPickUpRevolver, canBeRandomed, maxSprintTime, canSeeTime,
                neutralForKiller, neutralForInnocent, mafiaTeam,
                canUseInstinct, instinctNightVision, canSeeTeammateKiller,
                otherModeRole, hiddenForRotation, occupiedRoleCount, specialMapRole,
                occupationKeys, opposingKeys, relatedKeys, true, skills,
                skills != null && !skills.isEmpty());
    }

    public CompiledModifyOverlay(
            int color, MoodType mood,
            @Nullable com.habitrain.core.api.role.v2.patch.ColorPatch colorProvider,
            @Nullable FlagsPatch flagsPatch, @Nullable SpawnInfoPatch spawnInfoPatch,
            @Nullable NamePatch namePatch, @Nullable RoleTextPatch descriptionPatch,
            @Nullable RoleTextPatch simpleDescriptionPatch, @Nullable DefaultItemsPatch defaultItemsPatch,
            @Nullable ShopPatch shopPatch, @Nullable ShopTransform shopTransform,
            @Nullable WinConditionHook winConditionHook,
            boolean innocent, boolean canUseKiller, boolean neutral,
            boolean vigilanteTeam, int defaultMax, int enableChance, int needPlayerCount,
            int maxPlayerCount, boolean canSeeCoin, boolean canPickUpRevolver, boolean canBeRandomed,
            int maxSprintTime, boolean canSeeTime, boolean neutralForKiller, boolean neutralForInnocent,
            boolean mafiaTeam, boolean canUseInstinct, boolean instinctNightVision,
            boolean canSeeTeammateKiller, boolean otherModeRole, boolean hiddenForRotation,
            int occupiedRoleCount, SpecialMapRoleMap specialMapRole,
            List<RoleKey> occupationKeys, List<RoleKey> opposingKeys, List<RoleKey> relatedKeys,
            boolean opposingTwoWay, List<RoleSkillSpec> skills, boolean skillsSpecified) {
        this.color = color;
        this.colorProvider = colorProvider;
        this.flagsPatch = flagsPatch;
        this.spawnInfoPatch = spawnInfoPatch;
        this.mood = mood;
        this.namePatch = namePatch;
        this.descriptionPatch = descriptionPatch;
        this.simpleDescriptionPatch = simpleDescriptionPatch;
        this.defaultItemsPatch = defaultItemsPatch;
        this.shopPatch = shopPatch;
        this.shopTransform = shopTransform;
        this.winConditionHook = winConditionHook;
        this.innocent = innocent;
        this.canUseKiller = canUseKiller;
        this.neutral = neutral;
        this.vigilanteTeam = vigilanteTeam;
        this.defaultMax = defaultMax;
        this.enableChance = enableChance;
        this.needPlayerCount = needPlayerCount;
        this.maxPlayerCount = maxPlayerCount;
        this.canSeeCoin = canSeeCoin;
        this.canPickUpRevolver = canPickUpRevolver;
        this.canBeRandomed = canBeRandomed;
        this.maxSprintTime = maxSprintTime;
        this.canSeeTime = canSeeTime;
        this.neutralForKiller = neutralForKiller;
        this.neutralForInnocent = neutralForInnocent;
        this.mafiaTeam = mafiaTeam;
        this.canUseInstinct = canUseInstinct;
        this.instinctNightVision = instinctNightVision;
        this.canSeeTeammateKiller = canSeeTeammateKiller;
        this.otherModeRole = otherModeRole;
        this.hiddenForRotation = hiddenForRotation;
        this.occupiedRoleCount = occupiedRoleCount;
        this.specialMapRole = specialMapRole;
        this.occupationKeys = List.copyOf(Objects.requireNonNullElse(occupationKeys, List.of()));
        this.opposingKeys = List.copyOf(Objects.requireNonNullElse(opposingKeys, List.of()));
        this.relatedKeys = List.copyOf(Objects.requireNonNullElse(relatedKeys, List.of()));
        this.opposingTwoWay = opposingTwoWay;
        this.skills = List.copyOf(Objects.requireNonNullElse(skills, List.of()));
        this.skillsSpecified = skillsSpecified;
    }

    public int color() { return color; }
    public @Nullable com.habitrain.core.api.role.v2.patch.ColorPatch colorProvider() { return colorProvider; }
    public @Nullable FlagsPatch flagsPatch() { return flagsPatch; }
    public @Nullable SpawnInfoPatch spawnInfoPatch() { return spawnInfoPatch; }
    public MoodType mood() { return mood; }
    public @Nullable NamePatch namePatch() { return namePatch; }
    public @Nullable RoleTextPatch descriptionPatch() { return descriptionPatch; }
    public @Nullable RoleTextPatch simpleDescriptionPatch() { return simpleDescriptionPatch; }
    public @Nullable DefaultItemsPatch defaultItemsPatch() { return defaultItemsPatch; }
    public @Nullable ShopPatch shopPatch() { return shopPatch; }
    public @Nullable ShopTransform shopTransform() { return shopTransform; }
    public @Nullable WinConditionHook winConditionHook() { return winConditionHook; }
    public boolean innocent() { return innocent; }
    public boolean canUseKiller() { return canUseKiller; }
    public boolean neutral() { return neutral; }
    public boolean vigilanteTeam() { return vigilanteTeam; }
    public int defaultMax() { return defaultMax; }
    public int enableChance() { return enableChance; }
    public int needPlayerCount() { return needPlayerCount; }
    public int maxPlayerCount() { return maxPlayerCount; }
    public boolean canSeeCoin() { return canSeeCoin; }
    public boolean canPickUpRevolver() { return canPickUpRevolver; }
    public boolean canBeRandomed() { return canBeRandomed; }
    public int maxSprintTime() { return maxSprintTime; }
    public boolean canSeeTime() { return canSeeTime; }
    public boolean neutralForKiller() { return neutralForKiller; }
    public boolean neutralForInnocent() { return neutralForInnocent; }
    public boolean mafiaTeam() { return mafiaTeam; }
    public boolean canUseInstinct() { return canUseInstinct; }
    public boolean instinctNightVision() { return instinctNightVision; }
    public boolean canSeeTeammateKiller() { return canSeeTeammateKiller; }
    public boolean otherModeRole() { return otherModeRole; }
    public boolean hiddenForRotation() { return hiddenForRotation; }
    public int occupiedRoleCount() { return occupiedRoleCount; }
    public SpecialMapRoleMap specialMapRole() { return specialMapRole; }
    public List<RoleKey> occupationKeys() { return occupationKeys; }
    public List<RoleKey> opposingKeys() { return opposingKeys; }
    public List<RoleKey> relatedKeys() { return relatedKeys; }
    public boolean opposingTwoWay() { return opposingTwoWay; }
    public List<RoleSkillSpec> skills() { return skills; }
    public boolean skillsSpecified() { return skillsSpecified; }

    /** Whether any relation key was folded by a patch. */
    public boolean hasRelationKeys() {
        return !occupationKeys.isEmpty() || !opposingKeys.isEmpty() || !relatedKeys.isEmpty();
    }

    /** Whether any skill spec was folded by a patch. */
    public boolean hasSkills() {
        return skillsSpecified;
    }

    /**
     * Named construction. Unset primitives are zero/false and collections empty;
     * use {@link #toBuilder()} to edit an existing compiled value.
     */
    public static Builder builder(MoodType mood, SpecialMapRoleMap specialMapRole) {
        return new Builder(mood, specialMapRole);
    }

    /** Copies every field, including explicit empty lists and callback handles. */
    public Builder toBuilder() {
        return builder(mood, specialMapRole)
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
                .skillsSpecified(skillsSpecified);
    }

    /** Mutable named builder. Only build() publishes the immutable value. */
    public static final class Builder {
        private int color;
        private MoodType mood;
        private com.habitrain.core.api.role.v2.patch.ColorPatch colorProvider;
        private FlagsPatch flagsPatch;
        private SpawnInfoPatch spawnInfoPatch;
        private NamePatch namePatch;
        private RoleTextPatch descriptionPatch;
        private RoleTextPatch simpleDescriptionPatch;
        private DefaultItemsPatch defaultItemsPatch;
        private ShopPatch shopPatch;
        private ShopTransform shopTransform;
        private WinConditionHook winConditionHook;
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

        private Builder(MoodType mood, SpecialMapRoleMap specialMapRole) {
            this.mood = Objects.requireNonNull(mood, "mood");
            this.specialMapRole = Objects.requireNonNull(specialMapRole, "specialMapRole");
        }

        public Builder color(int value) { this.color = value; return this; }
        public Builder colorProvider(com.habitrain.core.api.role.v2.patch.ColorPatch value) { this.colorProvider = value; return this; }
        public Builder flagsPatch(FlagsPatch value) { this.flagsPatch = value; return this; }
        public Builder spawnInfoPatch(SpawnInfoPatch value) { this.spawnInfoPatch = value; return this; }
        public Builder namePatch(NamePatch value) { this.namePatch = value; return this; }
        public Builder descriptionPatch(RoleTextPatch value) { this.descriptionPatch = value; return this; }
        public Builder simpleDescriptionPatch(RoleTextPatch value) { this.simpleDescriptionPatch = value; return this; }
        public Builder defaultItemsPatch(DefaultItemsPatch value) { this.defaultItemsPatch = value; return this; }
        public Builder shopPatch(ShopPatch value) { this.shopPatch = value; return this; }
        public Builder shopTransform(ShopTransform value) { this.shopTransform = value; return this; }
        public Builder winConditionHook(WinConditionHook value) { this.winConditionHook = value; return this; }
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

        public CompiledModifyOverlay build() {
            return new CompiledModifyOverlay(
                    color, mood, colorProvider, flagsPatch,
                    spawnInfoPatch, namePatch, descriptionPatch, simpleDescriptionPatch,
                    defaultItemsPatch, shopPatch, shopTransform, winConditionHook,
                    innocent, canUseKiller, neutral, vigilanteTeam,
                    defaultMax, enableChance, needPlayerCount, maxPlayerCount,
                    canSeeCoin, canPickUpRevolver, canBeRandomed, maxSprintTime,
                    canSeeTime, neutralForKiller, neutralForInnocent, mafiaTeam,
                    canUseInstinct, instinctNightVision, canSeeTeammateKiller, otherModeRole,
                    hiddenForRotation, occupiedRoleCount, specialMapRole, occupationKeys,
                    opposingKeys, relatedKeys, opposingTwoWay, skills,
                    skillsSpecified);
        }
    }
}
