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

/**
 * Process-wide client-extension registry. Types are common so tests can
 * register HUD / instinct rules without a client; {@link #loadProviders()}
 * is only called from {@code HabiTrainCoreClient}.
 */
public final class RoleClientExtensionRegistry implements RoleClientExtensionApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleClientExtensionApi");
    public static final String ENTRYPOINT_KEY = "habitrain:role_client_extensions";

    private final Map<ResourceLocation, RoleHudSpec> huds = new LinkedHashMap<>();
    private final Map<ResourceLocation, RoleInstinctRule> instincts = new LinkedHashMap<>();
    private final Map<ResourceLocation, RoleSkinSpec> skins = new LinkedHashMap<>();
    private final Map<ResourceLocation, RoleNameRenderRule> nameRenders = new LinkedHashMap<>();
    private final Map<RoleKey, List<RoleHudWidget>> hudWidgets = new LinkedHashMap<>();
    private final Map<ResourceLocation, RoleScreenSpec> screens = new LinkedHashMap<>();
    private volatile boolean frozen;
    private volatile boolean loaded;

    public RoleClientExtensionRegistry() {}

    @Override
    public synchronized void loadProviders() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (var container : FabricLoader.getInstance()
                .getEntrypointContainers(ENTRYPOINT_KEY, RoleClientExtensionEntrypoint.class)) {
            try {
                container.getEntrypoint().register(this);
            } catch (RuntimeException e) {
                LOGGER.error("Role client extension {} failed",
                        container.getEntrypoint().getClass().getName(), e);
            }
        }
        LOGGER.info("Loaded {} HUD spec(s), {} instinct rule(s), {} skin(s), {} screen(s)",
                huds.size(), instincts.size(), skins.size(), screens.size());
    }

    @Override
    public synchronized void hud(RoleHudSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (frozen) {
            throw new IllegalStateException("Role client extension registry is frozen");
        }
        if (huds.putIfAbsent(spec.id(), spec) != null) {
            throw new IllegalArgumentException("Duplicate HUD spec: " + spec.id());
        }
    }

    @Override
    public synchronized void instinct(RoleInstinctRule rule) {
        Objects.requireNonNull(rule, "rule");
        if (frozen) {
            throw new IllegalStateException("Role client extension registry is frozen");
        }
        if (instincts.putIfAbsent(rule.id(), rule) != null) {
            throw new IllegalArgumentException("Duplicate instinct rule: " + rule.id());
        }
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
            if (role.equals(spec.role())) {
                out.add(spec);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void skin(RoleSkinSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (frozen) {
            throw new IllegalStateException("Role client extension registry is frozen");
        }
        if (skins.putIfAbsent(spec.id(), spec) != null) {
            throw new IllegalArgumentException("Duplicate skin spec: " + spec.id());
        }
    }

    @Override
    public synchronized void nameRender(RoleNameRenderRule rule) {
        Objects.requireNonNull(rule, "rule");
        if (frozen) {
            throw new IllegalStateException("Role client extension registry is frozen");
        }
        if (nameRenders.putIfAbsent(rule.id(), rule) != null) {
            throw new IllegalArgumentException("Duplicate name-render rule: " + rule.id());
        }
    }

    @Override
    public synchronized void hudWidget(RoleKey role, RoleHudWidget widget) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(widget, "widget");
        if (frozen) {
            throw new IllegalStateException("Role client extension registry is frozen");
        }
        hudWidgets.computeIfAbsent(role, k -> new ArrayList<>()).add(widget);
    }

    @Override
    public synchronized void screen(RoleScreenSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (frozen) {
            throw new IllegalStateException("Role client extension registry is frozen");
        }
        if (screens.putIfAbsent(spec.id(), spec) != null) {
            throw new IllegalArgumentException("Duplicate screen spec: " + spec.id());
        }
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
            if (viewerRole.equals(rule.viewerRole())) {
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
            if (role.equals(spec.role())) {
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
            if (role.equals(spec.role()) && kind == spec.kind()) {
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
            if (role.equals(rule.role())) {
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
        List<RoleHudWidget> widgets = hudWidgets.get(role);
        return widgets == null ? List.of() : List.copyOf(widgets);
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
            if (role.equals(spec.role())) {
                out.add(spec);
            }
        }
        return List.copyOf(out);
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
        instincts.clear();
        skins.clear();
        nameRenders.clear();
        hudWidgets.clear();
        screens.clear();
        frozen = false;
        loaded = false;
    }
}
