package com.habitrain.core.role.override;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.patch.FlagsPatch;
import com.habitrain.core.api.role.patch.SpawnInfoPatch;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.server.MinecraftServer;

/**
 * Server tick applier that applies MODIFY flags and spawnInfo patches
 * to SRERole instances. Runs on each server tick to ensure patches
 * are applied even if the role object is recreated or refreshed.
 */
public final class RoleOverrideTickApplier {
    private RoleOverrideTickApplier() {}

    public static void tick(MinecraftServer server) {
        RoleOverrideEngine engine = RoleOverrideEngine.getInstance();
        for (var entry : engine.getSnapshot().getActiveModifies().entrySet()) {
            SRERole role = TMMRoles.getRole(entry.getKey());
            if (role == null) continue;
            ModifyRoleDefinition def = entry.getValue();
            def.flagsPatch().ifPresent(p -> applyFlags(role, server, p));
            def.spawnInfoPatch().ifPresent(p -> applySpawnInfo(role, server, p));
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
    }

    private static void applySpawnInfo(SRERole role, MinecraftServer server, SpawnInfoPatch patch) {
        SpawnInfoPatch.MutableSpawnInfoPatch out = new SpawnInfoPatch.MutableSpawnInfoPatch();
        patch.apply(role, server, out);
        if (out.defaultMax != null) role.defaultMaxCount = out.defaultMax;
        if (out.defaultEnableChance != null) role.defaultEnableChance = out.defaultEnableChance;
        if (out.defaultEnableNeededPlayerCount != null) role.defaultEnableNeedPlayerCount = out.defaultEnableNeededPlayerCount;
        if (out.defaultEnableMaxPlayerCount != null) role.defaultEnableMaxPlayerCount = out.defaultEnableMaxPlayerCount;
    }
}
