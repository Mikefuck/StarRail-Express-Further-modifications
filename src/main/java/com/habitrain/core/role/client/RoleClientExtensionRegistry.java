package com.habitrain.core.role.client;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.client.RoleClientExtensionApi;
import com.habitrain.core.api.role.v2.client.RoleClientExtensionEntrypoint;
import com.habitrain.core.api.role.v2.client.RoleHudSpec;
import com.habitrain.core.api.role.v2.client.RoleHudWidget;
import com.habitrain.core.api.role.v2.client.RoleInstinctRule;
import com.habitrain.core.api.role.v2.client.RoleNameRenderRule;
import com.habitrain.core.api.role.v2.client.RoleScreenSpec;
import com.habitrain.core.api.role.v2.client.RoleSkinKind;
import com.habitrain.core.api.role.v2.client.RoleSkinSpec;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Process-wide client-extension registry. Provider writes are accepted only
 * through transactional {@link ScopedRoleClientExtensionRegistrar} instances;
 * {@link #loadProviders()} is only called from {@code HabiTrainCoreClient}.
 */
public final class RoleClientExtensionRegistry implements RoleClientExtensionApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleClientExtensionApi");
    public static final String ENTRYPOINT_KEY = "habitrain:role_client_extensions";

    private final Map<ResourceLocation, RoleHudSpec> huds = new LinkedHashMap<>();
    private final Map<ResourceLocation, String> hudProviders = new LinkedHashMap<>();
    private final Map<ResourceLocation, RoleInstinctRule> instincts = new LinkedHashMap<>();
    private final Map<ResourceLocation, String> instinctProviders = new LinkedHashMap<>();
    private final Map<ResourceLocation, RoleSkinSpec> skins = new LinkedHashMap<>();
    private final Map<ResourceLocation, String> skinProviders = new LinkedHashMap<>();
    private final Map<ResourceLocation, RoleNameRenderRule> nameRenders = new LinkedHashMap<>();
    private final Map<ResourceLocation, String> nameRenderProviders = new LinkedHashMap<>();
    private final Map<ResourceLocation, ManagedHudWidget> hudWidgets = new LinkedHashMap<>();
    private final Map<ResourceLocation, RoleScreenSpec> screens = new LinkedHashMap<>();
    private final Map<ResourceLocation, String> screenProviders = new LinkedHashMap<>();
    private volatile boolean frozen;
    private volatile boolean loaded;
    /** Providers whose client-extension registration committed (audit P1-4). */
    private final Set<String> loadedProviders = new LinkedHashSet<>();
    /**
     * Server-synced active provider ids. {@code null} or an empty set is
     * fail-closed: no client extensions are visible until a snapshot arrives.
     */
    private volatile @Nullable Set<String> activeProviders;
    /**
     * Active server entry ids from {@code RoleSnapshotPayload.EntryRow#entryId()}.
     * {@code null} with a non-null provider set means every entry of those
     * providers is visible; an empty set hides every entry.
     */
    private volatile @Nullable Set<String> activeEntryKeys;

    public RoleClientExtensionRegistry() {}

    @Override
    public synchronized void loadProviders() {
        if (loaded) {
            return;
        }
        if (!com.habitrain.core.internal.CoreLifecycleScope.isActive()) {
            LOGGER.warn("RoleClientExtensionApi.loadProviders() ignored: only habitrain_core bootstrap may load providers");
            return;
        }
        loaded = true;
        List<net.fabricmc.loader.api.entrypoint.EntrypointContainer<RoleClientExtensionEntrypoint>> containers =
                new ArrayList<>(FabricLoader.getInstance()
                        .getEntrypointContainers(ENTRYPOINT_KEY, RoleClientExtensionEntrypoint.class));
        containers.sort(java.util.Comparator
                .comparing((net.fabricmc.loader.api.entrypoint.EntrypointContainer<RoleClientExtensionEntrypoint> c) ->
                        c.getProvider().getMetadata().getId())
                .thenComparing(c -> String.valueOf(c.getDefinition())));
        for (var container : containers) {
            String providerId = container.getProvider().getMetadata().getId();
            ScopedRoleClientExtensionRegistrar scoped = new ScopedRoleClientExtensionRegistrar(providerId, this);
            try {
                com.habitrain.core.internal.CoreLifecycleScope.runUnprivileged(
                        () -> container.getEntrypoint().register(scoped));
                scoped.commit();
                loadedProviders.add(providerId);
            } catch (Throwable e) {
                scoped.rollback();
                LOGGER.error("Role client extension provider {} failed", providerId, e);
            }
        }
        LOGGER.info("Loaded {} HUD spec(s), {} instinct rule(s), {} skin(s), {} screen(s)",
                huds.size(), instincts.size(), skins.size(), screens.size());
    }

    /** Provider mod ids whose client-extension registration committed (audit P1-4). */
    @Override
    public synchronized Set<String> loadedProviderIds() {
        return Set.copyOf(loadedProviders);
    }

    @Override
    public synchronized void hud(RoleHudSpec spec) {
        throw directRegistrationUnsupported();
    }

    @Override
    public synchronized void instinct(RoleInstinctRule rule) {
        throw directRegistrationUnsupported();
    }

    @Override
    public synchronized Collection<RoleHudSpec> huds() {
        return List.copyOf(huds.values());
    }

    @Override
    public synchronized List<RoleHudSpec> hudsFor(RoleKey role) {
        if (role == null) {
            return List.of();
        }
        RoleKey canonical = canonicalizeRole(role);
        List<RoleHudSpec> out = new ArrayList<>();
        for (RoleHudSpec spec : huds.values()) {
            if (matchesRoleKey(spec.role(), role, canonical)
                    && isActive(hudProviders.get(spec.id()), spec.role(), spec.entryKey())) {
                out.add(spec);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void skin(RoleSkinSpec spec) {
        throw directRegistrationUnsupported();
    }

    @Override
    public synchronized void nameRender(RoleNameRenderRule rule) {
        throw directRegistrationUnsupported();
    }

    @Override
    public synchronized void hudWidget(ResourceLocation id, String entryKey, RoleKey role, RoleHudWidget widget) {
        throw directRegistrationUnsupported();
    }

    @Override
    public synchronized void hudWidget(RoleKey role, RoleHudWidget widget) {
        throw directRegistrationUnsupported();
    }

    @Override
    public synchronized void screen(RoleScreenSpec spec) {
        throw directRegistrationUnsupported();
    }

    @Override
    public synchronized Collection<RoleInstinctRule> instincts() {
        return List.copyOf(instincts.values());
    }

    @Override
    public synchronized List<RoleInstinctRule> instinctsFor(RoleKey viewerRole) {
        if (viewerRole == null) {
            return List.of();
        }
        RoleKey canonical = canonicalizeRole(viewerRole);
        List<RoleInstinctRule> out = new ArrayList<>();
        for (RoleInstinctRule rule : instincts.values()) {
            if (matchesRoleKey(rule.viewerRole(), viewerRole, canonical)
                    && isActive(instinctProviders.get(rule.id()), rule.viewerRole(), rule.entryKey())) {
                out.add(rule);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized Collection<RoleSkinSpec> skins() {
        return List.copyOf(skins.values());
    }

    @Override
    public synchronized List<RoleSkinSpec> skinsFor(RoleKey role) {
        if (role == null) {
            return List.of();
        }
        RoleKey canonical = canonicalizeRole(role);
        List<RoleSkinSpec> out = new ArrayList<>();
        for (RoleSkinSpec spec : skins.values()) {
            if (matchesRoleKey(spec.role(), role, canonical)
                    && isActive(skinProviders.get(spec.id()), spec.role(), spec.entryKey())) {
                out.add(spec);
            }
        }
        return List.copyOf(out);
    }

    /** First matching skin for {@code role} + {@code kind}, or {@code null}. */
    public synchronized @Nullable RoleSkinSpec skinFor(RoleKey role, RoleSkinKind kind) {
        if (role == null || kind == null) {
            return null;
        }
        RoleKey canonical = canonicalizeRole(role);
        for (RoleSkinSpec spec : skins.values()) {
            if (matchesRoleKey(spec.role(), role, canonical) && kind == spec.kind()
                    && isActive(skinProviders.get(spec.id()), spec.role(), spec.entryKey())) {
                return spec;
            }
        }
        return null;
    }

    @Override
    public synchronized Collection<RoleNameRenderRule> nameRenders() {
        return List.copyOf(nameRenders.values());
    }

    @Override
    public synchronized List<RoleNameRenderRule> nameRendersFor(RoleKey role) {
        if (role == null) {
            return List.of();
        }
        RoleKey canonical = canonicalizeRole(role);
        List<RoleNameRenderRule> out = new ArrayList<>();
        for (RoleNameRenderRule rule : nameRenders.values()) {
            if (matchesRoleKey(rule.role(), role, canonical)
                    && isActive(nameRenderProviders.get(rule.id()), rule.role(), rule.entryKey())) {
                out.add(rule);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized Collection<RoleHudWidget> hudWidgetsFor(RoleKey role) {
        if (role == null) {
            return List.of();
        }
        RoleKey canonical = canonicalizeRole(role);
        List<RoleHudWidget> out = new ArrayList<>();
        for (ManagedHudWidget managed : hudWidgets.values()) {
            if (matchesRoleKey(managed.role(), role, canonical)
                    && isActive(managed.provider(), managed.role(), managed.entryKey())) {
                out.add(managed.widget());
            }
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized Collection<RoleScreenSpec> screens() {
        return List.copyOf(screens.values());
    }

    @Override
    public synchronized List<RoleScreenSpec> screensFor(RoleKey role) {
        if (role == null) {
            return List.of();
        }
        RoleKey canonical = canonicalizeRole(role);
        List<RoleScreenSpec> out = new ArrayList<>();
        for (RoleScreenSpec spec : screens.values()) {
            if (matchesRoleKey(spec.role(), role, canonical)
                    && isActive(screenProviders.get(spec.id()), spec.role(), spec.entryKey())) {
                out.add(spec);
            }
        }
        return List.copyOf(out);
    }

    private static RoleKey canonicalizeRole(RoleKey role) {
        try {
            return com.habitrain.core.api.role.v2.RoleCatalogApi.instance().canonicalize(role.location());
        } catch (Throwable ignored) {
            return role;
        }
    }

    private static boolean matchesRoleKey(@Nullable RoleKey specRole, RoleKey queryRole, RoleKey canonicalQuery) {
        if (specRole == null) {
            return false;
        }
        if (specRole.equals(queryRole) || specRole.equals(canonicalQuery)) {
            return true;
        }
        RoleKey canonicalSpec = canonicalizeRole(specRole);
        return canonicalSpec.equals(canonicalQuery);
    }

    /**
     * Binds the server-synced active provider and entry sets. A {@code null}
     * provider set is fail-closed (same as empty). A {@code null} entry set is
     * only “all entries of an active provider” when the provider set is non-null.
     */
    public synchronized void setActiveProviders(@Nullable Set<String> providers,
                                                @Nullable Set<String> entryKeys) {
        this.activeProviders = providers == null ? null : Set.copyOf(providers);
        this.activeEntryKeys = entryKeys == null ? null : Set.copyOf(entryKeys);
        this.activeEntryIndex = entryKeys == null ? null : buildActiveEntryIndex(entryKeys);
    }

    /**
     * Prefix index for {@code provider$key@target} entry keys, grouped by the
     * provider segment, plus the exact-match (legacy/role-id) leftovers. Per-frame
     * {@link #isActive} then scans only the one provider's entries instead of the
     * whole set (review L2).
     */
    private volatile @Nullable ActiveEntryIndex activeEntryIndex;

    private static final class ActiveEntryIndex {
        final Map<String, List<String>> byProvider = new java.util.HashMap<>();
        final Set<String> exact = new java.util.HashSet<>();
    }

    private static ActiveEntryIndex buildActiveEntryIndex(Set<String> entryKeys) {
        ActiveEntryIndex index = new ActiveEntryIndex();
        for (String entry : entryKeys) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            int dollar = entry.indexOf('$');
            if (dollar > 0) {
                index.byProvider
                        .computeIfAbsent(entry.substring(0, dollar), k -> new java.util.ArrayList<>())
                        .add(entry);
            } else {
                index.exact.add(entry);
            }
        }
        return index;
    }

    /** Package-private prevalidation used by {@link ScopedRoleClientExtensionRegistrar}. */
    synchronized void validateHud(@Nullable String provider, RoleHudSpec spec) {
        Objects.requireNonNull(spec, "spec");
        requireNotFrozen();
        requireProviderNamespace(provider, spec.id().getNamespace());
        if (huds.containsKey(spec.id())) {
            throw new IllegalArgumentException("Duplicate HUD spec: " + spec.id());
        }
    }

    synchronized void validateInstinct(@Nullable String provider, RoleInstinctRule rule) {
        Objects.requireNonNull(rule, "rule");
        requireNotFrozen();
        requireProviderNamespace(provider, rule.id().getNamespace());
        if (instincts.containsKey(rule.id())) {
            throw new IllegalArgumentException("Duplicate instinct rule: " + rule.id());
        }
    }

    synchronized void validateSkin(@Nullable String provider, RoleSkinSpec spec) {
        Objects.requireNonNull(spec, "spec");
        requireNotFrozen();
        requireProviderNamespace(provider, spec.id().getNamespace());
        if (skins.containsKey(spec.id())) {
            throw new IllegalArgumentException("Duplicate skin spec: " + spec.id());
        }
    }

    synchronized void validateNameRender(@Nullable String provider, RoleNameRenderRule rule) {
        Objects.requireNonNull(rule, "rule");
        requireNotFrozen();
        requireProviderNamespace(provider, rule.id().getNamespace());
        if (nameRenders.containsKey(rule.id())) {
            throw new IllegalArgumentException("Duplicate name-render rule: " + rule.id());
        }
    }

    synchronized void validateHudWidget(@Nullable String provider, ResourceLocation id,
                                        @Nullable String entryKey, RoleKey role, RoleHudWidget widget) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(widget, "widget");
        requireNotFrozen();
        requireProviderNamespace(provider, id.getNamespace());
        validateEntryKey(provider, entryKey);
        if (hudWidgets.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate HUD widget: " + id);
        }
    }

    synchronized void validateScreen(@Nullable String provider, RoleScreenSpec spec) {
        Objects.requireNonNull(spec, "spec");
        requireNotFrozen();
        requireProviderNamespace(provider, spec.id().getNamespace());
        if (screens.containsKey(spec.id())) {
            throw new IllegalArgumentException("Duplicate screen spec: " + spec.id());
        }
    }

    private static void validateEntryKey(@Nullable String provider, @Nullable String entryKey) {
        if (provider == null || entryKey == null || entryKey.isBlank()) {
            return;
        }
        try {
            ResourceLocation.fromNamespaceAndPath(provider, entryKey);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid client extension entryKey for " + provider
                    + ": " + entryKey, e);
        }
    }

    private void requireNotFrozen() {
        if (frozen) {
            throw new IllegalStateException("Role client extension registry is frozen");
        }
    }

    private static void requireProviderNamespace(@Nullable String provider, String namespace) {
        if (provider != null && !provider.equals(namespace)) {
            throw new IllegalArgumentException("Client extension id namespace " + namespace
                    + " must match provider " + provider);
        }
    }

    /** Package-private commit path used by {@link ScopedRoleClientExtensionRegistrar}. */
    synchronized void commitHud(@Nullable String provider, RoleHudSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (frozen) {
            throw new IllegalStateException("Role client extension registry is frozen");
        }
        if (huds.putIfAbsent(spec.id(), spec) != null) {
            throw new IllegalArgumentException("Duplicate HUD spec: " + spec.id());
        }
        hudProviders.put(spec.id(), provider);
    }

    synchronized void commitInstinct(@Nullable String provider, RoleInstinctRule rule) {
        Objects.requireNonNull(rule, "rule");
        if (frozen) {
            throw new IllegalStateException("Role client extension registry is frozen");
        }
        if (instincts.putIfAbsent(rule.id(), rule) != null) {
            throw new IllegalArgumentException("Duplicate instinct rule: " + rule.id());
        }
        instinctProviders.put(rule.id(), provider);
    }

    synchronized void commitSkin(@Nullable String provider, RoleSkinSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (frozen) {
            throw new IllegalStateException("Role client extension registry is frozen");
        }
        if (skins.putIfAbsent(spec.id(), spec) != null) {
            throw new IllegalArgumentException("Duplicate skin spec: " + spec.id());
        }
        skinProviders.put(spec.id(), provider);
    }

    synchronized void commitNameRender(@Nullable String provider, RoleNameRenderRule rule) {
        Objects.requireNonNull(rule, "rule");
        if (frozen) {
            throw new IllegalStateException("Role client extension registry is frozen");
        }
        if (nameRenders.putIfAbsent(rule.id(), rule) != null) {
            throw new IllegalArgumentException("Duplicate name-render rule: " + rule.id());
        }
        nameRenderProviders.put(rule.id(), provider);
    }

    synchronized void commitHudWidget(@Nullable String provider, ResourceLocation id,
                                       @Nullable String entryKey, RoleKey role, RoleHudWidget widget) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(widget, "widget");
        if (frozen) {
            throw new IllegalStateException("Role client extension registry is frozen");
        }
        if (entryKey == null || entryKey.isBlank()) {
            entryKey = role.location().getPath();
        }
        if (hudWidgets.putIfAbsent(id, new ManagedHudWidget(provider, entryKey, role, widget)) != null) {
            throw new IllegalArgumentException("Duplicate HUD widget: " + id);
        }
    }

    synchronized void commitScreen(@Nullable String provider, RoleScreenSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (frozen) {
            throw new IllegalStateException("Role client extension registry is frozen");
        }
        if (screens.putIfAbsent(spec.id(), spec) != null) {
            throw new IllegalArgumentException("Duplicate screen spec: " + spec.id());
        }
        screenProviders.put(spec.id(), provider);
    }

    private static UnsupportedOperationException directRegistrationUnsupported() {
        return new UnsupportedOperationException(
                "Direct client-extension registration is not supported; register through "
                        + "the habitrain:role_client_extensions entrypoint and its provider-scoped registrar");
    }

    private boolean isActive(@Nullable String provider, RoleKey role, @Nullable String entryKey) {
        Set<String> active = activeProviders;
        if (active == null) {
            return false;
        }
        if (provider == null || !active.contains(provider)) {
            return false;
        }
        Set<String> entries = activeEntryKeys;
        if (entries == null) {
            return true;
        }
        if (entryKey != null && !entryKey.isBlank()) {
            boolean isDefaultRolePath = entryKey.equals(role.location().getPath());
            String targetSuffix = "@" + role.location();
            ActiveEntryIndex index = activeEntryIndex;
            if (index != null) {
                // Prefix matches can only live under this provider's group; legacy
                // shape / bare role ids live in the exact set (review L2).
                String serverShape = provider + "$" + entryKey + "@";
                List<String> providerEntries = index.byProvider.get(provider);
                if (providerEntries != null) {
                    for (String activeEntry : providerEntries) {
                        if (activeEntry.startsWith(serverShape)
                                || (isDefaultRolePath && activeEntry.endsWith(targetSuffix))) {
                            return true;
                        }
                    }
                }
                return index.exact.contains(legacyEntryKey(provider, role))
                        || index.exact.contains(role.location().toString());
            }
            for (String activeEntry : entries) {
                if (matchesActiveEntry(provider, entryKey, role, activeEntry)
                        || (isDefaultRolePath && activeEntry != null
                                && activeEntry.startsWith(provider + "$")
                                && activeEntry.endsWith(targetSuffix))) {
                    return true;
                }
            }
            return false;
        }
        String targetSuffix = "@" + role.location();
        ActiveEntryIndex index = activeEntryIndex;
        if (index != null) {
            List<String> providerEntries = index.byProvider.get(provider);
            if (providerEntries != null) {
                for (String activeEntry : providerEntries) {
                    if (activeEntry.endsWith(targetSuffix)) {
                        return true;
                    }
                }
            }
        } else {
            for (String activeEntry : entries) {
                if (activeEntry != null && activeEntry.startsWith(provider + "$")
                        && activeEntry.endsWith(targetSuffix)) {
                    return true;
                }
            }
        }
        return entries.contains(legacyEntryKey(provider, role))
                || entries.contains(role.location().toString());
    }

    /**
     * Matches a client declaration to the real server entry id. Explicit
     * {@code entryKey}s are compared against the same {@code provider$key@target}
     * shape used by server MODIFY/REPLACE rows; legacy declarations without an
     * entry key still accept the old provider:role approximation and ADD role ids.
     */
    private static boolean matchesActiveEntry(String provider, String entryKey, RoleKey role,
                                              String activeEntry) {
        if (activeEntry == null || activeEntry.isBlank()) {
            return false;
        }
        String serverShape = provider + "$" + entryKey + "@";
        if (activeEntry.startsWith(serverShape)) {
            return true;
        }
        return activeEntry.equals(legacyEntryKey(provider, role))
                || activeEntry.equals(role.location().toString());
    }

    private static String legacyEntryKey(String provider, RoleKey role) {
        return provider + ":" + role.location();
    }

    @Override
    public synchronized void freeze() {
        requireCoreCaller("RoleClientExtensionApi.freeze()");
        this.frozen = true;
    }

    private static void requireCoreCaller(String method) {
        if (com.habitrain.core.internal.CoreLifecycleScope.isActive()) {
            return;
        }
        Class<?> caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.skip(2)
                        .map(StackWalker.StackFrame::getDeclaringClass)
                        .findFirst()
                        .orElse(null));
        if (caller != null && caller.getName().startsWith("com.habitrain.core.")) {
            return;
        }
        throw new IllegalStateException(method + " may only be called by habitrain_core lifecycle");
    }

    @Override
    public boolean isFrozen() {
        return frozen;
    }

    public synchronized void clear() {
        huds.clear();
        hudProviders.clear();
        instincts.clear();
        instinctProviders.clear();
        skins.clear();
        skinProviders.clear();
        nameRenders.clear();
        nameRenderProviders.clear();
        hudWidgets.clear();
        screens.clear();
        screenProviders.clear();
        loadedProviders.clear();
        activeProviders = null;
        activeEntryKeys = null;
        activeEntryIndex = null;
        frozen = false;
        loaded = false;
    }

    private record ManagedHudWidget(@Nullable String provider, @Nullable String entryKey,
                                     RoleKey role, RoleHudWidget widget) {}
}
