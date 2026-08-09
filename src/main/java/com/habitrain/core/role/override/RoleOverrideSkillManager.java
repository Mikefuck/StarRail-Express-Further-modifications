package com.habitrain.core.role.override;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.patch.ManagedSkillPatch;
import com.habitrain.core.game.sre.mixin.RoleSkillUnifiedSkillsAccessor;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconciles declarative managed skill definitions without losing the role's
 * definitions that existed before the override was first activated.
 */
final class RoleOverrideSkillManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("RoleOverrideSkillManager");

    private static final Map<ResourceLocation, List<RoleSkill.Definition>> BASELINES = new HashMap<>();
    private static final Map<ResourceLocation, String> ACTIVE_ENTRY_IDS = new HashMap<>();

    private RoleOverrideSkillManager() {}

    static synchronized Set<ResourceLocation> reconcile(
            Map<ResourceLocation, ModifyRoleDefinition> next) {
        Set<ResourceLocation> failed = new HashSet<>();

        for (var old : new HashMap<>(ACTIVE_ENTRY_IDS).entrySet()) {
            ModifyRoleDefinition nextDef = next.get(old.getKey());
            String nextEntryId = nextDef == null ? null : RoleOverrideRegistry.entryId(nextDef);
            if (!old.getValue().equals(nextEntryId)) {
                restore(old.getKey());
            }
        }

        for (var entry : next.entrySet()) {
            ResourceLocation targetId = entry.getKey();
            ModifyRoleDefinition def = entry.getValue();
            if (def.managedSkillPatch().isEmpty()) continue;

            String entryId = RoleOverrideRegistry.entryId(def);
            if (entryId.equals(ACTIVE_ENTRY_IDS.get(targetId))) continue;

            SRERole role = TMMRoles.getRole(targetId);
            if (role == null) {
                failed.add(targetId);
                continue;
            }

            try {
                Map<ResourceLocation, List<RoleSkill.Definition>> unified =
                        RoleSkillUnifiedSkillsAccessor.habitrain$getUnifiedSkills();
                BASELINES.computeIfAbsent(targetId, ignored ->
                        List.copyOf(unified.getOrDefault(targetId, List.of())));
                List<RoleSkill.Definition> managed = def.managedSkillPatch().get().getDefinitions(role);
                if (managed == null) {
                    throw new IllegalStateException("ManagedSkillPatch returned null");
                }
                List<RoleSkill.Definition> effective = merge(
                        BASELINES.get(targetId), managed, def.managedSkillPatch().get().mode());
                replaceUnified(targetId, effective);
                ACTIVE_ENTRY_IDS.put(targetId, entryId);
            } catch (Throwable t) {
                LOGGER.error("Failed to apply managed skills for {}", targetId, t);
                restore(targetId);
                failed.add(targetId);
            }
        }
        return failed;
    }

    private static void restore(ResourceLocation targetId) {
        List<RoleSkill.Definition> baseline = BASELINES.get(targetId);
        if (baseline == null) {
            ACTIVE_ENTRY_IDS.remove(targetId);
            return;
        }
        try {
            replaceUnified(targetId, baseline);
        } catch (Throwable t) {
            LOGGER.error("Failed to restore baseline skills for {}", targetId, t);
        } finally {
            ACTIVE_ENTRY_IDS.remove(targetId);
        }
    }

    private static List<RoleSkill.Definition> merge(
            List<RoleSkill.Definition> baseline,
            List<RoleSkill.Definition> managed,
            ManagedSkillPatch.Mode mode) {
        List<RoleSkill.Definition> result = new ArrayList<>();
        if (mode == ManagedSkillPatch.Mode.APPEND) {
            result.addAll(baseline);
        } else if (mode == ManagedSkillPatch.Mode.REPLACE_MATCHING_IDS) {
            Set<ResourceLocation> replacements = new HashSet<>();
            managed.forEach(definition -> replacements.add(definition.id()));
            baseline.stream()
                    .filter(definition -> !replacements.contains(definition.id()))
                    .forEach(result::add);
        }
        result.addAll(managed);

        Set<ResourceLocation> ids = new HashSet<>();
        for (RoleSkill.Definition definition : result) {
            if (!ids.add(definition.id())) {
                throw new IllegalStateException("Duplicate managed skill id: " + definition.id());
            }
        }
        return List.copyOf(result);
    }

    private static void replaceUnified(
            ResourceLocation targetId, List<RoleSkill.Definition> definitions) {
        Map<ResourceLocation, List<RoleSkill.Definition>> unified =
                RoleSkillUnifiedSkillsAccessor.habitrain$getUnifiedSkills();
        Map<ResourceLocation, RoleSkill.SkillEntry> registry =
                RoleSkillUnifiedSkillsAccessor.habitrain$getSkillRegistry();
        registry.entrySet().removeIf(entry -> targetId.equals(entry.getValue().roleId()));

        if (definitions == null || definitions.isEmpty()) {
            unified.remove(targetId);
        } else {
            RoleSkill.register(targetId, definitions.toArray(RoleSkill.Definition[]::new));
        }
    }
}
