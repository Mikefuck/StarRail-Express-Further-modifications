package com.habitrain.core.role.override;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.OverrideStatus;
import com.habitrain.core.api.role.ReplaceRoleDefinition;
import com.habitrain.core.api.role.RoleOverrideEntry;
import com.habitrain.core.api.role.RoleOverrideKind;
import com.habitrain.core.config.RoleOverrideConfigSection;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RoleOverrideEngine {
    private static final RoleOverrideEngine INSTANCE = new RoleOverrideEngine();
    private static final Logger LOGGER = LoggerFactory.getLogger("RoleOverrideEngine");

    private volatile EffectiveSnapshot snapshot = new EffectiveSnapshot(Map.of(), Map.of(), List.of());

    /**
     * Monotonic version of the published snapshot, incremented once per rebuild.
     * Exposed to the v2 catalog so callers can tell when the effective role set
     * may have changed.
     */
    private volatile long snapshotVersion;

    /**
     * Replacement ids successfully adopted by core. Entries remain here after
     * deactivation because TMMRoles has no safe remove API.
     */
    private final Map<ResourceLocation, ManagedReplacement> managedReplacements = new LinkedHashMap<>();

    private RoleOverrideEngine() {}

    public static RoleOverrideEngine getInstance() { return INSTANCE; }

    public EffectiveSnapshot getSnapshot() { return snapshot; }

    public long getSnapshotVersion() { return snapshotVersion; }

    public synchronized void rebuild() {
        rebuild(com.habitrain.core.config.ConfigManager.getInstance().getRoleOverrides());
    }

    public synchronized void rebuild(@Nullable RoleOverrideConfigSection section) {
        boolean globalEnabled = section == null || section.isGlobalEnabled();
        List<ReplaceRoleDefinition> allReplaces = RoleOverrideRegistry.INSTANCE.getReplaces();
        List<ModifyRoleDefinition> allModifies = RoleOverrideRegistry.INSTANCE.getModifies();

        IdentityHashMap<Object, StatusInfo> statuses = new IdentityHashMap<>();
        Map<ResourceLocation, List<ReplaceRoleDefinition>> replaceByTarget = new LinkedHashMap<>();
        Map<ResourceLocation, List<ModifyRoleDefinition>> modifyByTarget = new LinkedHashMap<>();

        for (ReplaceRoleDefinition def : allReplaces) {
            String id = RoleOverrideRegistry.entryId(def);
            StatusInfo excluded = exclusionStatus(globalEnabled, section, id, def.targetRoleId());
            if (excluded != null) {
                statuses.put(def, excluded);
            } else {
                replaceByTarget.computeIfAbsent(def.targetRoleId(), ignored -> new ArrayList<>()).add(def);
            }
        }

        for (ModifyRoleDefinition def : allModifies) {
            String id = RoleOverrideRegistry.entryId(def);
            StatusInfo excluded = exclusionStatus(globalEnabled, section, id, def.targetRoleId());
            if (excluded != null) {
                statuses.put(def, excluded);
            } else {
                modifyByTarget.computeIfAbsent(def.targetRoleId(), ignored -> new ArrayList<>()).add(def);
            }
        }

        Map<ResourceLocation, ReplaceRoleDefinition> replaceCandidates = new LinkedHashMap<>();
        Map<ResourceLocation, ModifyRoleDefinition> modifyCandidates = new LinkedHashMap<>();
        Set<ResourceLocation> targets = new LinkedHashSet<>();
        targets.addAll(replaceByTarget.keySet());
        targets.addAll(modifyByTarget.keySet());

        for (ResourceLocation target : targets) {
            List<ReplaceRoleDefinition> replaces = replaceByTarget.getOrDefault(target, List.of());
            List<ModifyRoleDefinition> modifies = modifyByTarget.getOrDefault(target, List.of());
            boolean v2Owns = com.habitrain.core.role.extension.RoleExtensionRegistry.INSTANCE.isReplaced(target)
                    || com.habitrain.core.role.extension.RoleExtensionRegistry.INSTANCE.isModified(target);
            if (v2Owns && (!replaces.isEmpty() || !modifies.isEmpty())) {
                String message = "v1 override conflicts with a v2 REPLACE/MODIFY on " + target;
                replaces.forEach(def -> statuses.put(def,
                        new StatusInfo(OverrideStatus.CONFLICT, message)));
                modifies.forEach(def -> statuses.put(def,
                        new StatusInfo(OverrideStatus.CONFLICT, message)));
                LOGGER.warn("Conflict on target {}: {}", target, message);
                continue;
            }
            if (replaces.size() == 1 && modifies.isEmpty()) {
                replaceCandidates.put(target, replaces.get(0));
            } else if (modifies.size() == 1 && replaces.isEmpty()) {
                modifyCandidates.put(target, modifies.get(0));
            } else {
                String message = replaces.size() + " REPLACE(s), " + modifies.size()
                        + " MODIFY(s) enabled for " + target;
                replaces.forEach(def -> statuses.put(def,
                        new StatusInfo(OverrideStatus.CONFLICT, message)));
                modifies.forEach(def -> statuses.put(def,
                        new StatusInfo(OverrideStatus.CONFLICT, message)));
                LOGGER.warn("Conflict on target {}: {}", target, message);
            }
        }

        Map<ResourceLocation, ReplaceRoleDefinition> activeReplaces =
                activateReplacementCandidates(replaceCandidates, statuses);

        // Keep the legacy method for source/binary compatibility, but never
        // execute a one-way callback that Core cannot safely deactivate.
        for (var entry : new ArrayList<>(modifyCandidates.entrySet())) {
            ModifyRoleDefinition def = entry.getValue();
            if (def.skillRegistrar().isPresent()) {
                statuses.put(def, new StatusInfo(OverrideStatus.INVALID,
                        "Legacy skillRegistrar is not reversible; use managedSkillPatch"));
                modifyCandidates.remove(entry.getKey());
            }
        }

        Set<ResourceLocation> managedSkillFailures =
                RoleOverrideSkillManager.reconcile(modifyCandidates);
        for (ResourceLocation target : managedSkillFailures) {
            ModifyRoleDefinition failed = modifyCandidates.remove(target);
            if (failed != null) {
                statuses.put(failed, new StatusInfo(OverrideStatus.INVALID,
                        "Managed skill patch activation failed"));
            }
        }

        RoleOverrideTickApplier.reconcile(modifyCandidates);

        activeReplaces.values().forEach(def -> statuses.put(def,
                new StatusInfo(OverrideStatus.ACTIVE, null)));
        modifyCandidates.values().forEach(def -> statuses.put(def,
                new StatusInfo(OverrideStatus.ACTIVE, null)));

        List<RoleOverrideEntry> entries = new ArrayList<>(allReplaces.size() + allModifies.size());
        for (ReplaceRoleDefinition def : allReplaces) {
            StatusInfo status = statuses.getOrDefault(def,
                    new StatusInfo(OverrideStatus.INVALID, "Definition was not evaluated"));
            entries.add(toEntry(def, status.status, status.message));
        }
        for (ModifyRoleDefinition def : allModifies) {
            StatusInfo status = statuses.getOrDefault(def,
                    new StatusInfo(OverrideStatus.INVALID, "Definition was not evaluated"));
            entries.add(toEntry(def, status.status, status.message));
        }

        // Publish only after registration, skill reconciliation and baseline
        // restoration have completed.
        snapshot = new EffectiveSnapshot(activeReplaces, modifyCandidates, entries);
        snapshotVersion++;
        LOGGER.info("RoleOverrideEngine rebuilt: {} replaces, {} modifies active",
                activeReplaces.size(), modifyCandidates.size());
    }

    private @Nullable StatusInfo exclusionStatus(
            boolean globalEnabled,
            @Nullable RoleOverrideConfigSection section,
            String entryId,
            ResourceLocation targetId) {
        if (!globalEnabled) {
            return new StatusInfo(OverrideStatus.DISABLED, "Global role override switch is disabled");
        }
        if (section != null && !section.isEnabled(entryId)) {
            return new StatusInfo(OverrideStatus.DISABLED, "Entry is disabled");
        }
        if (TMMRoles.getRole(targetId) == null) {
            return new StatusInfo(OverrideStatus.INVALID, "Target role does not exist: " + targetId);
        }
        return null;
    }

    private Map<ResourceLocation, ReplaceRoleDefinition> activateReplacementCandidates(
            Map<ResourceLocation, ReplaceRoleDefinition> candidates,
            IdentityHashMap<Object, StatusInfo> statuses) {
        Map<ResourceLocation, ReplaceRoleDefinition> active = new LinkedHashMap<>();
        for (var entry : candidates.entrySet()) {
            ResourceLocation targetId = entry.getKey();
            ReplaceRoleDefinition def = entry.getValue();
            SRERole replacement = def.replacementRole();
            ResourceLocation replacementId = replacement.identifier();

            ManagedReplacement managed = managedReplacements.get(replacementId);
            SRERole registered = TMMRoles.getRole(replacementId);
            if (managed != null && managed.role != replacement) {
                statuses.put(def, new StatusInfo(OverrideStatus.INVALID,
                        "Replacement id is already managed by a different role: " + replacementId));
                continue;
            }
            if (registered != null && registered != replacement) {
                statuses.put(def, new StatusInfo(OverrideStatus.INVALID,
                        "Replacement id collides with an existing TMM role: " + replacementId));
                continue;
            }

            if (registered == null) {
                try {
                    TMMRoles.registerRole(replacement);
                    registered = TMMRoles.getRole(replacementId);
                } catch (Throwable t) {
                    LOGGER.error("Failed to register replacement role {}", replacementId, t);
                    statuses.put(def, new StatusInfo(OverrideStatus.INVALID,
                            "TMM role registration failed: " + t.getClass().getSimpleName()));
                    continue;
                }
                if (registered != replacement) {
                    statuses.put(def, new StatusInfo(OverrideStatus.INVALID,
                            "TMM role registration did not publish the replacement instance"));
                    continue;
                }
                LOGGER.info("Registered replacement role {}", replacementId);
            }

            managedReplacements.putIfAbsent(replacementId,
                    new ManagedReplacement(targetId, replacement));
            active.put(targetId, def);
        }
        return active;
    }

    public boolean isReplaced(ResourceLocation targetId) {
        return snapshot.getActiveReplaces().containsKey(targetId);
    }

    public @Nullable SRERole getReplacement(ResourceLocation targetId) {
        ReplaceRoleDefinition def = snapshot.getActiveReplaces().get(targetId);
        return def == null ? null : def.replacementRole();
    }

    public boolean isManagedReplacementId(ResourceLocation roleId) {
        synchronized (this) {
            return managedReplacements.containsKey(roleId);
        }
    }

    public boolean isActiveReplacementId(ResourceLocation roleId) {
        for (ReplaceRoleDefinition def : snapshot.getActiveReplaces().values()) {
            if (def.replacementRole().identifier().equals(roleId)) return true;
        }
        return false;
    }

    public @Nullable ResourceLocation getManagedTargetId(ResourceLocation replacementId) {
        synchronized (this) {
            ManagedReplacement managed = managedReplacements.get(replacementId);
            return managed == null ? null : managed.targetId;
        }
    }

    public boolean isModified(ResourceLocation targetId) {
        return snapshot.getActiveModifies().containsKey(targetId);
    }

    public @Nullable ModifyRoleDefinition getActiveModify(ResourceLocation targetId) {
        return snapshot.getActiveModifies().get(targetId);
    }

    /**
     * Returns every registered definition with ACTIVE, DISABLED, CONFLICT or
     * INVALID status, in registration order.
     */
    public Collection<RoleOverrideEntry> getEffectiveEntries() {
        return Collections.unmodifiableList(snapshot.getEntries());
    }

    private static RoleOverrideEntry toEntry(
            ReplaceRoleDefinition def, OverrideStatus status, @Nullable String message) {
        return new RoleOverrideEntry(
                RoleOverrideRegistry.entryId(def),
                def.sourceModId(),
                RoleOverrideKind.REPLACE,
                def.displayName(),
                def.targetRoleId(),
                Optional.of(def.replacementRole().identifier()),
                status,
                Optional.ofNullable(message)
        );
    }

    private static RoleOverrideEntry toEntry(
            ModifyRoleDefinition def, OverrideStatus status, @Nullable String message) {
        return new RoleOverrideEntry(
                RoleOverrideRegistry.entryId(def),
                def.sourceModId(),
                RoleOverrideKind.MODIFY,
                def.displayName(),
                def.targetRoleId(),
                Optional.empty(),
                status,
                Optional.ofNullable(message)
        );
    }

    private record ManagedReplacement(ResourceLocation targetId, SRERole role) {}
    private record StatusInfo(OverrideStatus status, @Nullable String message) {}
}
