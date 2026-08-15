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
import java.util.Set;

/**
 * Process-wide client-extension registry. Types are common so tests can
 * register HUD / instinct rules without a client; {@link #loadProviders()}
 * is only called from {@code HabiTrainCoreClient}.
 */
public final class RoleClientExtensionRegistry implements RoleClientExtensionApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleClientExtensionApi");
    public static final String ENTRYPOINT_KEY = "habitrain:role_client_extensions";
    private static final String DEFAULT_PROVIDER = "habitrain_core";

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
    private int hudWidgetSeq;
    /** Null means no server snapshot has arrived yet; all registered extensions are visible. */
    private volatile @Nullable Set<String> activeProviders;
    /** Active server entry ids from {@code RoleSnapshotPayload.EntryRow#entryId()}. */
    private volatile @Nullable Set<String> activeEntryKeys;

    public RoleClientExtensionRegistry() {}

    @Override
    public synchronized void loadProviders() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (var container : FabricLoader.getInstance()
                .getEntrypointContainers(ENTRYPOINT_KEY, RoleClientExtensionEntrypoint.class)) {
            String providerId = container.getProvider().getMetadata().getId();
            ScopedRoleClientExtensionRegistrar scoped = new ScopedRoleClientExtensionRegistrar(providerId, this);
            try {
                container.getEntrypoint().register(scoped);
                scoped.commit();
            } catch (RuntimeException e) {
                scoped.rollback();
                LOGGER.error("Role client extension provider {} failed", providerId, e);
            }
        }
        LOGGER.info("Loaded {} HUD spec(s), {} instinct rule(s), {} skin(s), {} screen(s)",
                huds.size(), instincts.size(), skins.size(), screens.size());
    }

    @Override
    public synchronized void hud(RoleHudSpec spec) {
        commitHud(DEFAULT_PROVIDER, spec);
    }

    @Override
    public synchronized void instinct(RoleInstinctRule rule) {
        commitInstinct(DEFAULT_PROVIDER, rule);
    }

    @Override
    public Collection<RoleHudSpec> huds() {
        return Collections.unmodifiableCollection(huds.values());
    }

    @Override
    public List<RoleHudSpec> hudsFor(RoleKey role) {
        if (role == null) {
            return List.of();
        }
        List<RoleHudSpec> out = new ArrayList<>();
        for (RoleHudSpec spec : huds.values()) {
            if (role.equals(spec.role()) && isActive(hudProviders.get(spec.id()), role, spec.entryKey())) {
                out.add(spec);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void skin(RoleSkinSpec spec) {
        commitSkin(DEFAULT_PROVIDER, spec);
    }

    @Override
    public synchronized void nameRender(RoleNameRenderRule rule) {
        commitNameRender(DEFAULT_PROVIDER, rule);
    }

    @Override
    public synchronized void hudWidget(ResourceLocation id, String entryKey, RoleKey role, RoleHudWidget widget) {
        commitHudWidget(DEFAULT_PROVIDER, id, entryKey, role, widget);
    }

    @Override
    public synchronized void hudWidget(RoleKey role, RoleHudWidget widget) {
        String path = role.location().getPath() + "/widget/" + (++hudWidgetSeq);
        hudWidget(ResourceLocation.fromNamespaceAndPath(DEFAULT_PROVIDER, path), null, role, widget);
    }

    @Override
    public synchronized void screen(RoleScreenSpec spec) {
        commitScreen(DEFAULT_PROVIDER, spec);
    }

    @Override
    public Collection<RoleInstinctRule> instincts() {
        return Collections.unmodifiableCollection(instincts.values());
    }

    @Override
    public List<RoleInstinctRule> instinctsFor(RoleKey viewerRole) {
        if (viewerRole == null) {
            return List.of();
        }
        List<RoleInstinctRule> out = new ArrayList<>();
        for (RoleInstinctRule rule : instincts.values()) {
            if (viewerRole.equals(rule.viewerRole())
                    && isActive(instinctProviders.get(rule.id()), viewerRole, rule.entryKey())) {
                out.add(rule);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public Collection<RoleSkinSpec> skins() {
        return Collections.unmodifiableCollection(skins.values());
    }

    @Override
    public List<RoleSkinSpec> skinsFor(RoleKey role) {
        if (role == null) {
            return List.of();
        }
        List<RoleSkinSpec> out = new ArrayList<>();
        for (RoleSkinSpec spec : skins.values()) {
            if (role.equals(spec.role()) && isActive(skinProviders.get(spec.id()), role, spec.entryKey())) {
                out.add(spec);
            }
        }
        return List.copyOf(out);
    }

    /** First matching skin for {@code role} + {@code kind}, or {@code null}. */
    public @Nullable RoleSkinSpec skinFor(RoleKey role, RoleSkinKind kind) {
        if (role == null || kind == null) {
            return null;
        }
        for (RoleSkinSpec spec : skins.values()) {
            if (role.equals(spec.role()) && kind == spec.kind()
                    && isActive(skinProviders.get(spec.id()), role, spec.entryKey())) {
                return spec;
            }
        }
        return null;
    }

    @Override
    public Collection<RoleNameRenderRule> nameRenders() {
        return Collections.unmodifiableCollection(nameRenders.values());
    }

    @Override
    public List<RoleNameRenderRule> nameRendersFor(RoleKey role) {
        if (role == null) {
            return List.of();
        }
        List<RoleNameRenderRule> out = new ArrayList<>();
        for (RoleNameRenderRule rule : nameRenders.values()) {
            if (role.equals(rule.role())
                    && isActive(nameRenderProviders.get(rule.id()), role, rule.entryKey())) {
                out.add(rule);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public Collection<RoleHudWidget> hudWidgetsFor(RoleKey role) {
        if (role == null) {
            return List.of();
        }
        List<RoleHudWidget> out = new ArrayList<>();
        for (ManagedHudWidget managed : hudWidgets.values()) {
            if (role.equals(managed.role()) && isActive(managed.provider(), role, managed.entryKey())) {
                out.add(managed.widget());
            }
        }
        return List.copyOf(out);
    }

    @Override
    public Collection<RoleScreenSpec> screens() {
        return Collections.unmodifiableCollection(screens.values());
    }

    @Override
    public List<RoleScreenSpec> screensFor(RoleKey role) {
        if (role == null) {
            return List.of();
        }
        List<RoleScreenSpec> out = new ArrayList<>();
        for (RoleScreenSpec spec : screens.values()) {
            if (role.equals(spec.role()) && isActive(screenProviders.get(spec.id()), role, spec.entryKey())) {
                out.add(spec);
            }
        }
        return List.copyOf(out);
    }

    /** Binds the server-synced active provider and entry sets. */
    public synchronized void setActiveProviders(@Nullable Set<String> providers,
                                                @Nullable Set<String> entryKeys) {
        this.activeProviders = providers == null ? null : Set.copyOf(providers);
        this.activeEntryKeys = entryKeys == null ? null : Set.copyOf(entryKeys);
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
        // Audit P1-2: stored + diagnosable, but no name-tag renderer consumes it yet.
        LOGGER.warn("[Experimental] name-render rule {} is stored but has no runtime "
                + "consumer yet (capability client_name_render)", rule.id());
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
        // Audit P1-2: stored + diagnosable, but no screen dispatcher consumes it yet.
        LOGGER.warn("[Experimental] screen spec {} is stored but has no runtime "
                + "consumer yet (capability client_screen)", spec.id());
    }

    private boolean isActive(@Nullable String provider, RoleKey role, @Nullable String entryKey) {
        Set<String> active = activeProviders;
        if (active == null) {
            return true;
        }
        if (provider == null || !active.contains(provider)) {
            return false;
        }
        Set<String> entries = activeEntryKeys;
        if (entries == null) {
            return true;
        }
        if (entryKey != null && !entryKey.isBlank()) {
            for (String activeEntry : entries) {
                if (matchesActiveEntry(provider, entryKey, role, activeEntry)) {
                    return true;
                }
            }
            return false;
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
        this.frozen = true;
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
        activeProviders = null;
        frozen = false;
        loaded = false;
    }

    private record ManagedHudWidget(@Nullable String provider, @Nullable String entryKey,
                                     RoleKey role, RoleHudWidget widget) {}
}