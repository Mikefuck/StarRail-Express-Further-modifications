package com.habitrain.core.api.role.v2.definition;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.book.RoleBookPatch;
import com.habitrain.core.api.role.v2.skill.RoleSkillPatch;
import io.wifi.starrailexpress.api.SRERole.MoodType;
import io.wifi.starrailexpress.api.SRERole.SpecialMapRoleMap;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A v2 {@code MODIFY} patch: a reversible, declarative overlay on an existing
 * canonical role. The target role keeps its id and original object; the patch
 * only layers field operations on top, so it can be fully undone when disabled.
 *
 * <p>Multiple providers may patch the same target. Patches are applied in
 * ascending {@link PatchPriority}, then by provider mod id, then by stable
 * {@code entryKey}. Each field carries an explicit merge operation (see the
 * nested {@link BooleanPatch} / {@link IntPatch} / {@link ColorPatch} /
 * {@link MoodPatch} / {@link RoleKeyListPatch} types) so two providers never
 * silently overwrite each other.
 */
public final class RolePatch {

    /** Merge operation for a boolean field. */
    public enum BooleanOp { SET, AND, OR }

    /** Merge operation for a numeric field. */
    public enum NumericOp { SET, ADD, MIN, MAX }

    /** A boolean field operation: {@code SET}, {@code AND} or {@code OR}. */
    public record BooleanPatch(BooleanOp op, boolean value) {
        public static BooleanPatch set(boolean v) { return new BooleanPatch(BooleanOp.SET, v); }
        public static BooleanPatch and(boolean v) { return new BooleanPatch(BooleanOp.AND, v); }
        public static BooleanPatch or(boolean v) { return new BooleanPatch(BooleanOp.OR, v); }
    }

    /** A numeric field operation: {@code SET}, {@code ADD}, {@code MIN} or {@code MAX}. */
    public record IntPatch(NumericOp op, int value) {
        public static IntPatch set(int v) { return new IntPatch(NumericOp.SET, v); }
        public static IntPatch add(int v) { return new IntPatch(NumericOp.ADD, v); }
        public static IntPatch min(int v) { return new IntPatch(NumericOp.MIN, v); }
        public static IntPatch max(int v) { return new IntPatch(NumericOp.MAX, v); }
    }

    /** A color override (announcement color). */
    public record ColorPatch(int color) {}

    /** A mood-type override. */
    public record MoodPatch(MoodType mood) {}

    /** A special-map override. */
    public record SpecialMapPatch(SpecialMapRoleMap map) {}

    /** A {@link RoleKey} list operation: {@link ListOp#APPEND}, {@link ListOp#REMOVE} or {@link ListOp#REPLACE_ALL}. */
    public record RoleKeyListPatch(ListOp op, List<RoleKey> keys) {
        public RoleKeyListPatch {
            Objects.requireNonNull(op, "op");
            keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
        }

        public static RoleKeyListPatch append(RoleKey... keys) {
            return new RoleKeyListPatch(ListOp.APPEND, List.of(keys));
        }

        public static RoleKeyListPatch remove(RoleKey... keys) {
            return new RoleKeyListPatch(ListOp.REMOVE, List.of(keys));
        }

        public static RoleKeyListPatch replaceAll(RoleKey... keys) {
            return new RoleKeyListPatch(ListOp.REPLACE_ALL, List.of(keys));
        }
    }

    private final RoleKey target;
    private final PatchPriority priority;
    private final String entryKey;
    private final @Nullable ColorPatch color;
    private final @Nullable MoodPatch mood;
    private final @Nullable BooleanPatch innocent;
    private final @Nullable BooleanPatch canUseKiller;
    private final @Nullable BooleanPatch neutral;
    private final @Nullable BooleanPatch vigilanteTeam;
    private final @Nullable IntPatch defaultMax;
    private final @Nullable IntPatch enableChance;
    private final @Nullable IntPatch needPlayerCount;
    private final @Nullable IntPatch maxPlayerCount;
    private final @Nullable BooleanPatch canSeeCoin;
    private final @Nullable BooleanPatch canPickUpRevolver;
    private final @Nullable BooleanPatch canBeRandomed;
    private final @Nullable IntPatch maxSprintTime;
    private final @Nullable BooleanPatch canSeeTime;
    private final @Nullable BooleanPatch neutralForKiller;
    private final @Nullable BooleanPatch neutralForInnocent;
    private final @Nullable BooleanPatch mafiaTeam;
    private final @Nullable BooleanPatch canUseInstinct;
    private final @Nullable BooleanPatch instinctNightVision;
    private final @Nullable BooleanPatch canSeeTeammateKiller;
    private final @Nullable BooleanPatch otherModeRole;
    private final @Nullable BooleanPatch hiddenForRotation;
    private final @Nullable IntPatch occupiedRoleCount;
    private final @Nullable SpecialMapPatch specialMapRole;
    private final @Nullable RoleKeyListPatch occupation;
    private final @Nullable RoleKeyListPatch opposing;
    private final @Nullable RoleKeyListPatch related;
    private final @Nullable RoleSkillPatch skills;
    private final @Nullable RoleBookPatch book;

    private RolePatch(Builder b) {
        this.target = Objects.requireNonNull(b.target, "target");
        this.priority = Objects.requireNonNull(b.priority, "priority");
        this.entryKey = b.entryKey;
        this.color = b.color;
        this.mood = b.mood;
        this.innocent = b.innocent;
        this.canUseKiller = b.canUseKiller;
        this.neutral = b.neutral;
        this.vigilanteTeam = b.vigilanteTeam;
        this.defaultMax = b.defaultMax;
        this.enableChance = b.enableChance;
        this.needPlayerCount = b.needPlayerCount;
        this.maxPlayerCount = b.maxPlayerCount;
        this.canSeeCoin = b.canSeeCoin;
        this.canPickUpRevolver = b.canPickUpRevolver;
        this.canBeRandomed = b.canBeRandomed;
        this.maxSprintTime = b.maxSprintTime;
        this.canSeeTime = b.canSeeTime;
        this.neutralForKiller = b.neutralForKiller;
        this.neutralForInnocent = b.neutralForInnocent;
        this.mafiaTeam = b.mafiaTeam;
        this.canUseInstinct = b.canUseInstinct;
        this.instinctNightVision = b.instinctNightVision;
        this.canSeeTeammateKiller = b.canSeeTeammateKiller;
        this.otherModeRole = b.otherModeRole;
        this.hiddenForRotation = b.hiddenForRotation;
        this.occupiedRoleCount = b.occupiedRoleCount;
        this.specialMapRole = b.specialMapRole;
        this.occupation = b.occupation;
        this.opposing = b.opposing;
        this.related = b.related;
        this.skills = b.skills;
        this.book = b.book;
    }

    public static Builder builder(RoleKey target) {
        return new Builder().target(target);
    }

    /** Convenience builder over a namespace/path pair (normalized by {@link RoleKey}). */
    public static Builder builder(String namespace, String path) {
        return builder(RoleKey.of(namespace, path));
    }

    /** Convenience builder over an already-normalized {@link ResourceLocation}. */
    public static Builder builder(ResourceLocation location) {
        return builder(RoleKey.of(location));
    }

    public RoleKey target() { return target; }
    public PatchPriority priority() { return priority; }
    public @Nullable String entryKey() { return entryKey; }
    public @Nullable ColorPatch color() { return color; }
    public @Nullable MoodPatch mood() { return mood; }
    public @Nullable BooleanPatch innocent() { return innocent; }
    public @Nullable BooleanPatch canUseKiller() { return canUseKiller; }
    public @Nullable BooleanPatch neutral() { return neutral; }
    public @Nullable BooleanPatch vigilanteTeam() { return vigilanteTeam; }
    public @Nullable IntPatch defaultMax() { return defaultMax; }
    public @Nullable IntPatch enableChance() { return enableChance; }
    public @Nullable IntPatch needPlayerCount() { return needPlayerCount; }
    public @Nullable IntPatch maxPlayerCount() { return maxPlayerCount; }
    public @Nullable BooleanPatch canSeeCoin() { return canSeeCoin; }
    public @Nullable BooleanPatch canPickUpRevolver() { return canPickUpRevolver; }
    public @Nullable BooleanPatch canBeRandomed() { return canBeRandomed; }
    public @Nullable IntPatch maxSprintTime() { return maxSprintTime; }
    public @Nullable BooleanPatch canSeeTime() { return canSeeTime; }
    public @Nullable BooleanPatch neutralForKiller() { return neutralForKiller; }
    public @Nullable BooleanPatch neutralForInnocent() { return neutralForInnocent; }
    public @Nullable BooleanPatch mafiaTeam() { return mafiaTeam; }
    public @Nullable BooleanPatch canUseInstinct() { return canUseInstinct; }
    public @Nullable BooleanPatch instinctNightVision() { return instinctNightVision; }
    public @Nullable BooleanPatch canSeeTeammateKiller() { return canSeeTeammateKiller; }
    public @Nullable BooleanPatch otherModeRole() { return otherModeRole; }
    public @Nullable BooleanPatch hiddenForRotation() { return hiddenForRotation; }
    public @Nullable IntPatch occupiedRoleCount() { return occupiedRoleCount; }
    public @Nullable SpecialMapPatch specialMapRole() { return specialMapRole; }
    public @Nullable RoleKeyListPatch occupation() { return occupation; }
    public @Nullable RoleKeyListPatch opposing() { return opposing; }
    public @Nullable RoleKeyListPatch related() { return related; }
    public @Nullable RoleSkillPatch skills() { return skills; }
    public @Nullable RoleBookPatch book() { return book; }

    /** Whether this patch carries at least one field operation. */
    public boolean isEmpty() {
        return color == null && mood == null && innocent == null && canUseKiller == null
                && neutral == null && vigilanteTeam == null && defaultMax == null
                && enableChance == null && needPlayerCount == null && maxPlayerCount == null
                && canSeeCoin == null && canPickUpRevolver == null && canBeRandomed == null
                && maxSprintTime == null && canSeeTime == null
                && neutralForKiller == null && neutralForInnocent == null && mafiaTeam == null
                && canUseInstinct == null && instinctNightVision == null && canSeeTeammateKiller == null
                && otherModeRole == null && hiddenForRotation == null && occupiedRoleCount == null
                && specialMapRole == null && occupation == null && opposing == null && related == null
                && skills == null && book == null;
    }

    public static final class Builder {
        private RoleKey target;
        private PatchPriority priority = PatchPriority.NORMAL;
        private String entryKey;
        private ColorPatch color;
        private MoodPatch mood;
        private BooleanPatch innocent;
        private BooleanPatch canUseKiller;
        private BooleanPatch neutral;
        private BooleanPatch vigilanteTeam;
        private IntPatch defaultMax;
        private IntPatch enableChance;
        private IntPatch needPlayerCount;
        private IntPatch maxPlayerCount;
        private BooleanPatch canSeeCoin;
        private BooleanPatch canPickUpRevolver;
        private BooleanPatch canBeRandomed;
        private IntPatch maxSprintTime;
        private BooleanPatch canSeeTime;
        private BooleanPatch neutralForKiller;
        private BooleanPatch neutralForInnocent;
        private BooleanPatch mafiaTeam;
        private BooleanPatch canUseInstinct;
        private BooleanPatch instinctNightVision;
        private BooleanPatch canSeeTeammateKiller;
        private BooleanPatch otherModeRole;
        private BooleanPatch hiddenForRotation;
        private IntPatch occupiedRoleCount;
        private SpecialMapPatch specialMapRole;
        private RoleKeyListPatch occupation;
        private RoleKeyListPatch opposing;
        private RoleKeyListPatch related;
        private RoleSkillPatch skills;
        private RoleBookPatch book;

        public Builder target(RoleKey target) { this.target = Objects.requireNonNull(target, "target"); return this; }
        public Builder priority(PatchPriority priority) { this.priority = Objects.requireNonNull(priority, "priority"); return this; }
        public Builder entryKey(String entryKey) { this.entryKey = entryKey; return this; }
        public Builder color(int color) { this.color = new ColorPatch(color); return this; }
        public Builder mood(MoodType mood) { this.mood = new MoodPatch(Objects.requireNonNull(mood, "mood")); return this; }
        public Builder innocent(BooleanPatch p) { this.innocent = p; return this; }
        public Builder canUseKiller(BooleanPatch p) { this.canUseKiller = p; return this; }
        public Builder neutral(BooleanPatch p) { this.neutral = p; return this; }
        public Builder vigilanteTeam(BooleanPatch p) { this.vigilanteTeam = p; return this; }
        public Builder defaultMax(IntPatch p) { this.defaultMax = p; return this; }
        public Builder enableChance(IntPatch p) { this.enableChance = p; return this; }
        public Builder needPlayerCount(IntPatch p) { this.needPlayerCount = p; return this; }
        public Builder maxPlayerCount(IntPatch p) { this.maxPlayerCount = p; return this; }
        public Builder canSeeCoin(BooleanPatch p) { this.canSeeCoin = p; return this; }
        public Builder canPickUpRevolver(BooleanPatch p) { this.canPickUpRevolver = p; return this; }
        public Builder canBeRandomed(BooleanPatch p) { this.canBeRandomed = p; return this; }
        public Builder maxSprintTime(IntPatch p) { this.maxSprintTime = p; return this; }
        public Builder canSeeTime(BooleanPatch p) { this.canSeeTime = p; return this; }
        public Builder neutralForKiller(BooleanPatch p) { this.neutralForKiller = p; return this; }
        public Builder neutralForInnocent(BooleanPatch p) { this.neutralForInnocent = p; return this; }
        public Builder mafiaTeam(BooleanPatch p) { this.mafiaTeam = p; return this; }
        public Builder canUseInstinct(BooleanPatch p) { this.canUseInstinct = p; return this; }
        public Builder instinctNightVision(BooleanPatch p) { this.instinctNightVision = p; return this; }
        public Builder canSeeTeammateKiller(BooleanPatch p) { this.canSeeTeammateKiller = p; return this; }
        public Builder otherModeRole(BooleanPatch p) { this.otherModeRole = p; return this; }
        public Builder hiddenForRotation(BooleanPatch p) { this.hiddenForRotation = p; return this; }
        public Builder occupiedRoleCount(IntPatch p) { this.occupiedRoleCount = p; return this; }
        public Builder specialMapRole(SpecialMapRoleMap map) {
            this.specialMapRole = new SpecialMapPatch(Objects.requireNonNull(map, "specialMapRole"));
            return this;
        }
        public Builder occupation(RoleKeyListPatch p) { this.occupation = p; return this; }
        public Builder opposing(RoleKeyListPatch p) { this.opposing = p; return this; }
        public Builder related(RoleKeyListPatch p) { this.related = p; return this; }
        public Builder skills(RoleSkillPatch p) { this.skills = p; return this; }
        public Builder book(RoleBookPatch p) { this.book = p; return this; }

        public RolePatch build() {
            if (target == null) throw new IllegalStateException("target required");
            RolePatch patch = new RolePatch(this);
            if (patch.isEmpty()) {
                throw new IllegalStateException("RolePatch must carry at least one field operation");
            }
            return patch;
        }
    }
}
