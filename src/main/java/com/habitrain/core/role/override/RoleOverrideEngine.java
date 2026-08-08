package com.habitrain.core.role.override;

import com.habitrain.core.api.role.*;
import com.habitrain.core.config.RoleOverrideConfigSection;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class RoleOverrideEngine {
    private static final RoleOverrideEngine INSTANCE = new RoleOverrideEngine();
    private static final Logger LOGGER = LoggerFactory.getLogger("RoleOverrideEngine");

    private EffectiveSnapshot snapshot = new EffectiveSnapshot(Map.of(), Map.of());

    private RoleOverrideEngine() {}

    public static RoleOverrideEngine getInstance() { return INSTANCE; }

    public EffectiveSnapshot getSnapshot() { return snapshot; }

    public void rebuild() {
        rebuild(com.habitrain.core.config.ConfigManager.getInstance().getRoleOverrides());
    }

    public void rebuild(@Nullable RoleOverrideConfigSection section) {
        boolean globalEnabled = section == null || section.isGlobalEnabled();
        Map<ResourceLocation, List<ReplaceRoleDefinition>> replaceByTarget = new HashMap<>();
        Map<ResourceLocation, List<ModifyRoleDefinition>> modifyByTarget = new HashMap<>();

        for (ReplaceRoleDefinition def : RoleOverrideRegistry.INSTANCE.getReplaces()) {
            if (!globalEnabled) continue;
            if (section != null && !section.isEnabled(entryId(def))) continue;
            if (TMMRoles.getRole(def.targetRoleId()) == null) continue;
            replaceByTarget.computeIfAbsent(def.targetRoleId(), k -> new ArrayList<>()).add(def);
        }

        for (ModifyRoleDefinition def : RoleOverrideRegistry.INSTANCE.getModifies()) {
            if (!globalEnabled) continue;
            if (section != null && !section.isEnabled(entryId(def))) continue;
            if (TMMRoles.getRole(def.targetRoleId()) == null) continue;
            modifyByTarget.computeIfAbsent(def.targetRoleId(), k -> new ArrayList<>()).add(def);
        }

        Map<ResourceLocation, ReplaceRoleDefinition> activeReplaces = new HashMap<>();
        Map<ResourceLocation, ModifyRoleDefinition> activeModifies = new HashMap<>();

        Set<ResourceLocation> targets = new HashSet<>();
        targets.addAll(replaceByTarget.keySet());
        targets.addAll(modifyByTarget.keySet());

        for (ResourceLocation target : targets) {
            List<ReplaceRoleDefinition> rs = replaceByTarget.getOrDefault(target, List.of());
            List<ModifyRoleDefinition> ms = modifyByTarget.getOrDefault(target, List.of());
            if (rs.size() == 1 && ms.isEmpty()) {
                activeReplaces.put(target, rs.get(0));
            } else if (ms.size() == 1 && rs.isEmpty()) {
                activeModifies.put(target, ms.get(0));
            } else {
                LOGGER.warn("Conflict on target {}: {} REPLACE(s), {} MODIFY(s); none activated",
                    target, rs.size(), ms.size());
            }
        }

        snapshot = new EffectiveSnapshot(activeReplaces, activeModifies);
        applySnapshot(snapshot);
        LOGGER.info("RoleOverrideEngine rebuilt: {} replaces, {} modifies active",
            activeReplaces.size(), activeModifies.size());
    }

    private void applySnapshot(EffectiveSnapshot snap) {
        for (ReplaceRoleDefinition def : snap.getActiveReplaces().values()) {
            SRERole role = def.replacementRole();
            if (TMMRoles.getRole(role.identifier()) == null) {
                TMMRoles.registerRole(role);
                LOGGER.info("Registered replacement role {}", role.identifier());
            }
        }
        for (ModifyRoleDefinition def : snap.getActiveModifies().values()) {
            def.skillRegistrar().ifPresent(reg -> reg.register(TMMRoles.getRole(def.targetRoleId())));
        }
    }

    public boolean isReplaced(ResourceLocation targetId) {
        return snapshot.getActiveReplaces().containsKey(targetId);
    }

    public @Nullable SRERole getReplacement(ResourceLocation targetId) {
        ReplaceRoleDefinition def = snapshot.getActiveReplaces().get(targetId);
        return def == null ? null : def.replacementRole();
    }

    public boolean isModified(ResourceLocation targetId) {
        return snapshot.getActiveModifies().containsKey(targetId);
    }

    public @Nullable ModifyRoleDefinition getActiveModify(ResourceLocation targetId) {
        return snapshot.getActiveModifies().get(targetId);
    }

    public Collection<RoleOverrideEntry> getEffectiveEntries() {
        List<RoleOverrideEntry> list = new ArrayList<>();
        for (ReplaceRoleDefinition def : snapshot.getActiveReplaces().values()) {
            list.add(toEntry(def, OverrideStatus.ACTIVE, null));
        }
        for (ModifyRoleDefinition def : snapshot.getActiveModifies().values()) {
            list.add(toEntry(def, OverrideStatus.ACTIVE, null));
        }
        return Collections.unmodifiableList(list);
    }

    public static String entryId(ReplaceRoleDefinition def) {
        ResourceLocation replId = def.replacementId().orElse(def.replacementRole().identifier());
        return def.sourceModId() + "$" + replId.getPath() + "@" + def.targetRoleId();
    }

    public static String entryId(ModifyRoleDefinition def) {
        return def.sourceModId() + "$" + def.targetRoleId().getPath() + "@" + def.targetRoleId();
    }

    private RoleOverrideEntry toEntry(ReplaceRoleDefinition def, OverrideStatus status, String msg) {
        return new RoleOverrideEntry(
            entryId(def), def.sourceModId(), RoleOverrideKind.REPLACE, def.displayName(),
            def.targetRoleId(), def.replacementId().or(() -> Optional.of(def.replacementRole().identifier())),
            status, Optional.ofNullable(msg)
        );
    }

    private RoleOverrideEntry toEntry(ModifyRoleDefinition def, OverrideStatus status, String msg) {
        return new RoleOverrideEntry(
            entryId(def), def.sourceModId(), RoleOverrideKind.MODIFY, def.displayName(),
            def.targetRoleId(), Optional.empty(),
            status, Optional.ofNullable(msg)
        );
    }
}
