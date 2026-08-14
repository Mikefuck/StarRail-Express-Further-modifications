package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.action.RoleActionApi;
import com.habitrain.core.api.role.v2.action.RoleActionSpec;
import com.habitrain.core.api.role.v2.behavior.RoleHooks;
import com.habitrain.core.api.role.v2.behavior.RoleScope;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityApi;
import com.habitrain.core.api.role.v2.capability.RoleChatPolicy;
import com.habitrain.core.api.role.v2.capability.RoleVoicePolicy;
import com.habitrain.core.api.role.v2.definition.PatchPriority;
import com.habitrain.core.api.role.v2.definition.ReplacementIdentity;
import com.habitrain.core.api.role.v2.definition.RoleAlias;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.role.behavior.RoleHookRegistry;
import com.habitrain.core.role.action.RoleActionServiceImpl;
import com.habitrain.core.role.capability.RoleCapabilityServiceImpl;
import com.habitrain.core.role.state.RoleStateServiceImpl;
import com.habitrain.core.api.role.v2.state.RoleStateKey;
import com.habitrain.core.api.role.v2.state.RoleStateSpec;
import com.habitrain.core.api.role.v2.state.RoleStateApi;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One provider's staged role-extension declarations (fix-doc §7.2).
 *
 * <p>Every declaration is validated and compiled at staging time but only written
 * to {@link RoleExtensionRegistry} (and, for {@code ADD}/{@code REPLACE}, to the
 * upstream {@code TMMRoles}) in {@link #commit()}. A provider callback that throws
 * rolls the transaction back, leaving zero entries and zero upstream leakage.
 *
 * <p>The {@code add} staging path is pure ({@link ManagedSRERole#from}); the
 * returned instance is exactly the object that {@link #commit()} later registers,
 * so a provider's identity assumptions ({@code HabiRoles.X == TMMRoles.getRole(id)})
 * hold after commit.
 */
public final class ProviderRegistrationTransaction {

    private static final Logger LOGGER = LoggerFactory.getLogger("ProviderRegistrationTransaction");

    private final String providerId;
    private final RoleExtensionRegistry registry;
    private final List<RoleDefinition> stagedAdds = new ArrayList<>();
    private final Map<ResourceLocation, ManagedSRERole> stagedAddRoles = new LinkedHashMap<>();
    private final List<RolePatch> stagedModifies = new ArrayList<>();
    private final List<RoleReplacement> stagedReplacements = new ArrayList<>();
    private final List<RoleAlias> stagedAliases = new ArrayList<>();
    private final List<StagedHooks> stagedHooks = new ArrayList<>();
    private final List<RoleStateSpec<?>> stagedStates = new ArrayList<>();
    private final List<RoleActionSpec> stagedActions = new ArrayList<>();
    private final List<RoleVoicePolicy> stagedVoices = new ArrayList<>();
    private final List<RoleChatPolicy> stagedChats = new ArrayList<>();
    private final Set<ResourceLocation> stagedStateKeys = new HashSet<>();
    private final Set<ResourceLocation> stagedActionIds = new HashSet<>();
    private final Set<ResourceLocation> stagedVoiceIds = new HashSet<>();
    private final Set<ResourceLocation> stagedChatIds = new HashSet<>();
    private int hookSeq;
    private final Set<String> stagedEntryIds = new HashSet<>();
    private final Map<ResourceLocation, String> stagedReplacementOwners = new LinkedHashMap<>();
    private boolean closed;

    ProviderRegistrationTransaction(String providerId, RoleExtensionRegistry registry) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** The provider mod id that owns every declaration staged here. */
    public String providerId() {
        return providerId;
    }

    /**
     * Stages an {@code ADD} role. The id namespace must equal the provider id
     * (strict ownership, replacing the looser "loaded mod" check). Compiles the
     * definition without touching {@code TMMRoles}.
     */
    public SRERole add(RoleDefinition def) {
        ensureOpen();
        Objects.requireNonNull(def, "def");
        ResourceLocation id = def.key().location();
        if (!id.getNamespace().equals(providerId)) {
            throw new IllegalArgumentException(
                    "ADD role id " + id + " must be in the provider's namespace " + providerId);
        }
        if (stagedAddRoles.containsKey(id) || registry.isAdded(id)) {
            throw new IllegalArgumentException("ADD role already registered: " + id);
        }
        ManagedSRERole role = ManagedSRERole.from(def);
        stagedAdds.add(def);
        stagedAddRoles.put(id, role);
        return role;
    }

    /** Stages a reversible {@code MODIFY} patch. */
    public void modify(RolePatch patch) {
        ensureOpen();
        Objects.requireNonNull(patch, "patch");
        RoleExtensionRegistry.validateEntryKey(providerId, patch.entryKey());
        String entryId = RoleExtensionRegistry.entryId(providerId, patch);
        if (!stagedEntryIds.add(entryId)) {
            throw new IllegalArgumentException("Duplicate MODIFY entryId: " + entryId
                    + "; set a distinct entryKey for each declaration");
        }
        stagedModifies.add(patch);
    }

    /** Stages a {@code REPLACE} operation. Only one owner may claim a target. */
    public void replace(RoleReplacement replacement) {
        ensureOpen();
        Objects.requireNonNull(replacement, "replacement");
        RoleExtensionRegistry.validateReplacementIdentity(providerId, replacement);
        RoleExtensionRegistry.validateEntryKey(providerId, replacement.entryKey());
        String entryId = RoleExtensionRegistry.entryId(providerId, replacement);
        if (!stagedEntryIds.add(entryId)) {
            throw new IllegalArgumentException("Duplicate REPLACE entryId: " + entryId
                    + "; set a distinct entryKey for each declaration");
        }
        ResourceLocation target = replacement.target().location();
        String previousOwner = stagedReplacementOwners.putIfAbsent(target, entryId);
        if (previousOwner != null) {
            throw new IllegalArgumentException("Replacement target " + target
                    + " is already owned by " + previousOwner);
        }
        stagedReplacements.add(replacement);
    }

    /** Stages an {@code ALIAS} redirect. The canonical target must be in the provider's namespace. */
    public void alias(RoleAlias alias) {
        ensureOpen();
        Objects.requireNonNull(alias, "alias");
        if (!alias.to().namespace().equals(providerId)) {
            throw new IllegalArgumentException("Alias target " + alias.to()
                    + " must be in the provider's namespace " + providerId);
        }
        String entryId = RoleExtensionRegistry.entryId(providerId, alias);
        if (!stagedEntryIds.add(entryId)) {
            throw new IllegalArgumentException("Duplicate ALIAS entryId: " + entryId);
        }
        stagedAliases.add(alias);
    }

    /**
     * Stages managed behavior hooks for a role. The provider id is captured from
     * the transaction and a provider-local entry id is assigned, so a provider's
     * hooks survive as their own ordered entry (never merged away) and roll back
     * with the rest of the transaction.
     */
    public void hooks(RoleKey role, RoleScope scope, RoleHooks hooks) {
        ensureOpen();
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(hooks, "hooks");
        if (hooks.isEmpty()) {
            return;
        }
        String entryId = providerId + "$hooks:" + role + "#" + (++hookSeq);
        stagedHooks.add(new StagedHooks(role, scope, hooks, entryId));
    }

    /** Stages a provider-owned state schema instead of immediately mutating the singleton. */
    public <T> RoleStateKey<T> state(RoleStateSpec<T> spec) {
        ensureOpen();
        Objects.requireNonNull(spec, "spec");
        requireOwnedId("state", spec.id());
        if (!stagedStateKeys.add(spec.id())) {
            throw new IllegalArgumentException("Duplicate staged role state: " + spec.id());
        }
        stagedStates.add(spec);
        return new RoleStateKey<>(spec.id(), spec.role(), spec.type());
    }

    /** Stages a provider-owned action schema instead of immediately mutating the singleton. */
    public RoleActionSpec action(RoleActionSpec spec) {
        ensureOpen();
        Objects.requireNonNull(spec, "spec");
        requireOwnedId("action", spec.id());
        if (!stagedActionIds.add(spec.id())) {
            throw new IllegalArgumentException("Duplicate staged role action: " + spec.id());
        }
        stagedActions.add(spec);
        return spec;
    }

    public RoleVoicePolicy voice(RoleVoicePolicy policy) {
        ensureOpen();
        Objects.requireNonNull(policy, "policy");
        requireOwnedId("voice policy", policy.id());
        if (!stagedVoiceIds.add(policy.id())) {
            throw new IllegalArgumentException("Duplicate staged voice policy: " + policy.id());
        }
        stagedVoices.add(policy);
        return policy;
    }

    public RoleChatPolicy chat(RoleChatPolicy policy) {
        ensureOpen();
        Objects.requireNonNull(policy, "policy");
        requireOwnedId("chat policy", policy.id());
        if (!stagedChatIds.add(policy.id())) {
            throw new IllegalArgumentException("Duplicate staged chat policy: " + policy.id());
        }
        stagedChats.add(policy);
        return policy;
    }

    /**
     * Applies every staged declaration in order. Before any physical write it
     * pre-scans all {@code ADD} and {@code NEW_ID_WITH_ALIAS} {@code REPLACE} ids
     * against upstream {@code TMMRoles}, so a collision discovered late aborts the
     * whole transaction with nothing registered.
     */
    public void commit() {
        ensureOpen();
        for (ResourceLocation id : stagedAddRoles.keySet()) {
            if (TMMRoles.getRole(id) != null) {
                throw new IllegalStateException("ADD role id already exists in TMMRoles: " + id);
            }
        }
        for (RoleReplacement replacement : stagedReplacements) {
            if (replacement.identity() == ReplacementIdentity.NEW_ID_WITH_ALIAS) {
                ResourceLocation rid = replacement.replacement().key().location();
                if (TMMRoles.getRole(rid) != null) {
                    throw new IllegalStateException(
                            "NEW_ID_WITH_ALIAS replacement id already exists in TMMRoles: " + rid);
                }
            }
        }
        RoleExtensionRegistry.RegistrationSnapshot roleSnapshot = registry.snapshotForTransaction();
        RoleHookRegistry.RegistrationSnapshot hookSnapshot = RoleHookRegistry.INSTANCE.snapshotForTransaction();
        RoleStateServiceImpl stateService = stateService();
        RoleActionServiceImpl actionService = actionService();
        RoleCapabilityServiceImpl capabilityService = capabilityService();
        RoleStateServiceImpl.RegistrationSnapshot stateSnapshot = stateService.snapshotForTransaction();
        RoleActionServiceImpl.RegistrationSnapshot actionSnapshot = actionService.snapshotForTransaction();
        RoleCapabilityServiceImpl.RegistrationSnapshot capabilitySnapshot = capabilityService.snapshotForTransaction();
        try {
            for (RoleDefinition def : stagedAdds) {
                registry.registerAdd(stagedAddRoles.get(def.key().location()));
            }
            for (RolePatch patch : stagedModifies) {
                registry.modify(providerId, patch);
            }
            for (RoleReplacement replacement : stagedReplacements) {
                registry.replace(providerId, replacement);
            }
            for (RoleAlias alias : stagedAliases) {
                registry.alias(providerId, alias);
            }
            for (StagedHooks h : stagedHooks) {
                RoleHookRegistry.INSTANCE.register(h.role(), h.scope(), providerId, h.entryId(),
                        PatchPriority.NORMAL, h.hooks());
            }
            for (RoleStateSpec<?> spec : stagedStates) {
                registerState(stateService, spec);
            }
            for (RoleActionSpec spec : stagedActions) {
                actionService.register(spec);
            }
            for (RoleVoicePolicy policy : stagedVoices) {
                capabilityService.voice(policy);
            }
            for (RoleChatPolicy policy : stagedChats) {
                capabilityService.chat(policy);
            }
            closed = true;
            LOGGER.info("Committed role extension declarations for provider {}", providerId);
        } catch (RuntimeException | Error e) {
            capabilityService.restoreTransactionSnapshot(capabilitySnapshot);
            actionService.restoreTransactionSnapshot(actionSnapshot);
            stateService.restoreTransactionSnapshot(stateSnapshot);
            RoleHookRegistry.INSTANCE.restoreTransactionSnapshot(hookSnapshot);
            registry.restoreTransactionSnapshot(roleSnapshot);
            closed = true;
            throw e;
        }
    }

    /** Discards all staged declarations. No upstream registry was touched. */
    public void rollback() {
        closed = true; // terminal; nothing was written
        LOGGER.info("Rolled back role extension declarations for provider {}", providerId);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Transaction for provider " + providerId + " is already closed");
        }
    }

    private void requireOwnedId(String kind, ResourceLocation id) {
        if (id == null || !providerId.equals(id.getNamespace())) {
            throw new IllegalArgumentException(kind + " id " + id
                    + " must be in the provider namespace " + providerId);
        }
    }

    private static RoleStateServiceImpl stateService() {
        return (RoleStateServiceImpl) RoleStateApi.instance();
    }

    private static RoleActionServiceImpl actionService() {
        return (RoleActionServiceImpl) RoleActionApi.instance();
    }

    private static RoleCapabilityServiceImpl capabilityService() {
        return (RoleCapabilityServiceImpl) RoleCapabilityApi.instance();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerState(RoleStateServiceImpl service, RoleStateSpec<?> spec) {
        service.register((RoleStateSpec) spec);
    }

    private record StagedHooks(RoleKey role, RoleScope scope, RoleHooks hooks, String entryId) {}
}
