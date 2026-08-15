package com.habitrain.core.role.client;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.client.RoleClientExtensionRegistrar;
import com.habitrain.core.api.role.v2.client.RoleHudSpec;
import com.habitrain.core.api.role.v2.client.RoleHudWidget;
import com.habitrain.core.api.role.v2.client.RoleInstinctRule;
import com.habitrain.core.api.role.v2.client.RoleNameRenderRule;
import com.habitrain.core.api.role.v2.client.RoleScreenSpec;
import com.habitrain.core.api.role.v2.client.RoleSkinSpec;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Provider-scoped client-extension registrar. Entrypoint providers receive this
 * instead of the global registry, so a provider that throws mid-registration
 * cannot leave partial HUD / skin / rule effects behind.
 */
final class ScopedRoleClientExtensionRegistrar implements RoleClientExtensionRegistrar {

    private final String providerId;
    private final RoleClientExtensionRegistry registry;
    private final List<RoleHudSpec> huds = new ArrayList<>();
    private final List<RoleInstinctRule> instincts = new ArrayList<>();
    private final List<RoleSkinSpec> skins = new ArrayList<>();
    private final List<RoleNameRenderRule> nameRenders = new ArrayList<>();
    private final List<HudWidgetEntry> hudWidgets = new ArrayList<>();
    private final List<RoleScreenSpec> screens = new ArrayList<>();
    private boolean closed;
    private int generatedWidgetSeq;

    ScopedRoleClientExtensionRegistrar(String providerId, RoleClientExtensionRegistry registry) {
        this.providerId = providerId;
        this.registry = registry;
    }

    @Override
    public void hud(RoleHudSpec spec) {
        requireOpen();
        huds.add(spec);
    }

    @Override
    public void instinct(RoleInstinctRule rule) {
        requireOpen();
        instincts.add(rule);
    }

    @Override
    public void skin(RoleSkinSpec spec) {
        requireOpen();
        skins.add(spec);
    }

    @Override
    public void nameRender(RoleNameRenderRule rule) {
        requireOpen();
        nameRenders.add(rule);
    }

    @Override
    public void hudWidget(ResourceLocation id, String entryKey, RoleKey role, RoleHudWidget widget) {
        requireOpen();
        if (id == null) {
            id = ResourceLocation.fromNamespaceAndPath(providerId, "widgets/" + (++generatedWidgetSeq));
        }
        if (entryKey == null || entryKey.isBlank()) {
            entryKey = role == null ? null : role.location().getPath();
        }
        hudWidgets.add(new HudWidgetEntry(id, entryKey, role, widget));
    }

    @Override
    public void hudWidget(RoleKey role, RoleHudWidget widget) {
        hudWidget(null, null, role, widget);
    }

    @Override
    public void screen(RoleScreenSpec spec) {
        requireOpen();
        screens.add(spec);
    }

    @Override
    public Collection<RoleHudSpec> huds() {
        return List.copyOf(huds);
    }

    @Override
    public List<RoleHudSpec> hudsFor(RoleKey role) {
        return huds.stream().filter(s -> role.equals(s.role())).toList();
    }

    @Override
    public Collection<RoleInstinctRule> instincts() {
        return List.copyOf(instincts);
    }

    @Override
    public List<RoleInstinctRule> instinctsFor(RoleKey viewerRole) {
        return instincts.stream().filter(r -> viewerRole.equals(r.viewerRole())).toList();
    }

    @Override
    public Collection<RoleSkinSpec> skins() {
        return List.copyOf(skins);
    }

    @Override
    public List<RoleSkinSpec> skinsFor(RoleKey role) {
        return skins.stream().filter(s -> role.equals(s.role())).toList();
    }

    @Override
    public Collection<RoleNameRenderRule> nameRenders() {
        return List.copyOf(nameRenders);
    }

    @Override
    public List<RoleNameRenderRule> nameRendersFor(RoleKey role) {
        return nameRenders.stream().filter(r -> role.equals(r.role())).toList();
    }

    @Override
    public Collection<RoleHudWidget> hudWidgetsFor(RoleKey role) {
        return hudWidgets.stream().filter(e -> role.equals(e.role())).map(HudWidgetEntry::widget).toList();
    }

    @Override
    public Collection<RoleScreenSpec> screens() {
        return List.copyOf(screens);
    }

    @Override
    public List<RoleScreenSpec> screensFor(RoleKey role) {
        return screens.stream().filter(s -> role.equals(s.role())).toList();
    }

    void commit() {
        if (closed) {
            return;
        }
        closed = true;
        // Validate the whole batch before writing anything, so a duplicate within
        // this same provider batch, a global collision, a namespace error or a
        // frozen registry cannot leave earlier entries partially committed.
        Set<ResourceLocation> stagedHudIds = new HashSet<>();
        Set<ResourceLocation> stagedInstinctIds = new HashSet<>();
        Set<ResourceLocation> stagedSkinIds = new HashSet<>();
        Set<ResourceLocation> stagedNameRenderIds = new HashSet<>();
        Set<ResourceLocation> stagedScreenIds = new HashSet<>();
        Set<ResourceLocation> stagedHudWidgetIds = new HashSet<>();
        for (RoleHudSpec spec : huds) {
            if (!stagedHudIds.add(spec.id())) {
                throw new IllegalArgumentException("Duplicate HUD spec in provider batch: " + spec.id());
            }
            registry.validateHud(providerId, spec);
        }
        for (RoleInstinctRule rule : instincts) {
            if (!stagedInstinctIds.add(rule.id())) {
                throw new IllegalArgumentException("Duplicate instinct rule in provider batch: " + rule.id());
            }
            registry.validateInstinct(providerId, rule);
        }
        for (RoleSkinSpec spec : skins) {
            if (!stagedSkinIds.add(spec.id())) {
                throw new IllegalArgumentException("Duplicate skin spec in provider batch: " + spec.id());
            }
            registry.validateSkin(providerId, spec);
        }
        for (RoleNameRenderRule rule : nameRenders) {
            if (!stagedNameRenderIds.add(rule.id())) {
                throw new IllegalArgumentException("Duplicate name-render rule in provider batch: " + rule.id());
            }
            registry.validateNameRender(providerId, rule);
        }
        for (HudWidgetEntry entry : hudWidgets) {
            if (!stagedHudWidgetIds.add(entry.id())) {
                throw new IllegalArgumentException("Duplicate HUD widget in provider batch: " + entry.id());
            }
            registry.validateHudWidget(providerId, entry.id(), entry.entryKey(), entry.role(), entry.widget());
        }
        for (RoleScreenSpec spec : screens) {
            if (!stagedScreenIds.add(spec.id())) {
                throw new IllegalArgumentException("Duplicate screen spec in provider batch: " + spec.id());
            }
            registry.validateScreen(providerId, spec);
        }
        for (RoleHudSpec spec : huds) {
            registry.commitHud(providerId, spec);
        }
        for (RoleInstinctRule rule : instincts) {
            registry.commitInstinct(providerId, rule);
        }
        for (RoleSkinSpec spec : skins) {
            registry.commitSkin(providerId, spec);
        }
        for (RoleNameRenderRule rule : nameRenders) {
            registry.commitNameRender(providerId, rule);
        }
        for (HudWidgetEntry entry : hudWidgets) {
            registry.commitHudWidget(providerId, entry.id(), entry.entryKey(), entry.role(), entry.widget());
        }
        for (RoleScreenSpec spec : screens) {
            registry.commitScreen(providerId, spec);
        }
    }

    void rollback() {
        closed = true;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Role client extension registrar is closed");
        }
    }

    private record HudWidgetEntry(ResourceLocation id, String entryKey, RoleKey role, RoleHudWidget widget) {}
}
