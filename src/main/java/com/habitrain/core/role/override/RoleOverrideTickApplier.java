package com.habitrain.core.role.override;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.patch.FlagsPatch;
import com.habitrain.core.api.role.patch.SpawnInfoPatch;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * Server tick applier that applies MODIFY flags and spawnInfo patches
 * to SRERole instances. Runs on each server tick to ensure patches
 * are applied even if the role object is recreated or refreshed.
 */
public final class RoleOverrideTickApplier {
    private static final Map<ResourceLocation, Baseline> BASELINES = new HashMap<>();
    private static final Map<ResourceLocation, String> ACTIVE_ENTRY_IDS = new HashMap<>();
    private static final Map<ResourceLocation, SRERole> SERVER_APPLIED_OBJECTS = new HashMap<>();

    private RoleOverrideTickApplier() {}

    /**
     * Restores targets removed from the effective snapshot and captures an
     * immutable baseline before a new flags/spawn patch becomes active.
     */
    static synchronized void reconcile(Map<ResourceLocation, ModifyRoleDefinition> next) {
        for (var old : new HashMap<>(ACTIVE_ENTRY_IDS).entrySet()) {
            ModifyRoleDefinition nextDef = next.get(old.getKey());
            String nextEntryId = nextDef == null ? null : RoleOverrideRegistry.entryId(nextDef);
            if (!old.getValue().equals(nextEntryId)) {
                restore(old.getKey());
                SERVER_APPLIED_OBJECTS.remove(old.getKey());
            }
        }

        for (var entry : next.entrySet()) {
            ModifyRoleDefinition def = entry.getValue();
            if (def.flagsPatch().isEmpty() && def.spawnInfoPatch().isEmpty()) continue;
            String entryId = RoleOverrideRegistry.entryId(def);
            if (entryId.equals(ACTIVE_ENTRY_IDS.get(entry.getKey()))) continue;
            SRERole role = TMMRoles.getRole(entry.getKey());
            if (role == null) continue;
            capture(entry.getKey(), role);
            ACTIVE_ENTRY_IDS.put(entry.getKey(), entryId);
            // Client rebuilds have no MinecraftServer, but the role book and
            // role-category UI must observe the same flags/spawn metadata.
            def.flagsPatch().ifPresent(p -> applyFlags(role, null, p));
            def.spawnInfoPatch().ifPresent(p -> applySpawnInfo(role, null, p));
        }
    }

    public static synchronized void tick(MinecraftServer server) {
        RoleOverrideEngine engine = RoleOverrideEngine.getInstance();
        for (var entry : engine.getSnapshot().getActiveModifies().entrySet()) {
            SRERole role = TMMRoles.getRole(entry.getKey());
            if (role == null) continue;
            ModifyRoleDefinition def = entry.getValue();
            Baseline baseline = BASELINES.get(entry.getKey());
            // reconcile 已应用过当前对象；只在上游 refreshRoles 替换了对象时重放一次。
            if (baseline != null && baseline.role == role
                    && SERVER_APPLIED_OBJECTS.get(entry.getKey()) == role) continue;
            capture(entry.getKey(), role);
            def.flagsPatch().ifPresent(p -> applyFlags(role, server, p));
            def.spawnInfoPatch().ifPresent(p -> applySpawnInfo(role, server, p));
            SERVER_APPLIED_OBJECTS.put(entry.getKey(), role);
        }
    }

    private static void applyFlags(SRERole role, MinecraftServer server, FlagsPatch patch) {
        FlagsPatch.MutableFlagsPatch out = new FlagsPatch.MutableFlagsPatch();
        patch.apply(role, server, out);
        if (out.isInnocent != null) role.setInnocent(out.isInnocent);
        if (out.canUseKiller != null) role.setCanUseKiller(out.canUseKiller);
        if (out.isNeutrals != null) role.setNeutrals(out.isNeutrals);
        if (out.isVigilanteTeam != null) role.setVigilanteTeam(out.isVigilanteTeam);
        if (out.isNeutralForKiller != null) role.setNeutralForKiller(out.isNeutralForKiller);
        if (out.isNeutralForInnocent != null) role.setNeutralForInnocent(out.isNeutralForInnocent);
        if (out.canUseInstinct != null) role.setCanUseInstinct(out.canUseInstinct);
        if (out.instinctNightVision != null) role.setInstinctNightVision(out.instinctNightVision);
        if (out.canSeeTeammateKiller != null) role.setCanSeeTeammateKillerRole(out.canSeeTeammateKiller);
    }

    private static void applySpawnInfo(SRERole role, MinecraftServer server, SpawnInfoPatch patch) {
        SpawnInfoPatch.MutableSpawnInfoPatch out = new SpawnInfoPatch.MutableSpawnInfoPatch();
        patch.apply(role, server, out);
        if (out.defaultMax != null) role.defaultMaxCount = out.defaultMax;
        if (out.defaultEnableChance != null) role.defaultEnableChance = out.defaultEnableChance;
        if (out.defaultEnableNeededPlayerCount != null) role.defaultEnableNeedPlayerCount = out.defaultEnableNeededPlayerCount;
        if (out.defaultEnableMaxPlayerCount != null) role.defaultEnableMaxPlayerCount = out.defaultEnableMaxPlayerCount;
    }

    private static void capture(ResourceLocation id, SRERole role) {
        Baseline current = BASELINES.get(id);
        if (current != null && current.role == role) return;
        BASELINES.put(id, new Baseline(
                role,
                role.isInnocent(),
                role.canUseKiller(),
                role.isNeutrals(),
                role.isVigilanteTeam(),
                role.isNeutralForKiller(),
                role.isNeutralForInnocent(),
                role.canUseInstinct(),
                role.haveInstinctNightVision(),
                role.canSeeTeammateKillerRole(),
                role.defaultMaxCount,
                role.defaultEnableChance,
                role.defaultEnableNeedPlayerCount,
                role.defaultEnableMaxPlayerCount
        ));
    }

    private static void restore(ResourceLocation id) {
        Baseline baseline = BASELINES.get(id);
        if (baseline == null) {
            ACTIVE_ENTRY_IDS.remove(id);
            return;
        }
        SRERole role = TMMRoles.getRole(id);
        if (role == baseline.role) {
            role.setInnocent(baseline.isInnocent);
            role.setCanUseKiller(baseline.canUseKiller);
            role.setNeutrals(baseline.isNeutrals);
            role.setVigilanteTeam(baseline.isVigilanteTeam);
            role.setNeutralForKiller(baseline.isNeutralForKiller);
            role.setNeutralForInnocent(baseline.isNeutralForInnocent);
            role.setCanUseInstinct(baseline.canUseInstinct);
            role.setInstinctNightVision(baseline.instinctNightVision);
            role.setCanSeeTeammateKillerRole(baseline.canSeeTeammateKiller);
            role.defaultMaxCount = baseline.defaultMax;
            role.defaultEnableChance = baseline.defaultEnableChance;
            role.defaultEnableNeedPlayerCount = baseline.defaultEnableNeededPlayerCount;
            role.defaultEnableMaxPlayerCount = baseline.defaultEnableMaxPlayerCount;
        } else if (role != null) {
            // The registry object was recreated; its current values are its new baseline.
            capture(id, role);
        }
        ACTIVE_ENTRY_IDS.remove(id);
        SERVER_APPLIED_OBJECTS.remove(id);
    }

    private record Baseline(
            SRERole role,
            boolean isInnocent,
            boolean canUseKiller,
            boolean isNeutrals,
            boolean isVigilanteTeam,
            boolean isNeutralForKiller,
            boolean isNeutralForInnocent,
            boolean canUseInstinct,
            boolean instinctNightVision,
            boolean canSeeTeammateKiller,
            int defaultMax,
            int defaultEnableChance,
            int defaultEnableNeededPlayerCount,
            int defaultEnableMaxPlayerCount
    ) {}
}
