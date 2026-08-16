package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.definition.ReplacementIdentity;
import com.habitrain.core.api.role.v2.definition.RoleAlias;
import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.api.role.v2.skill.RoleSkillPatch;
import com.habitrain.core.api.role.v2.skill.RoleSkillSpec;
import com.habitrain.core.role.catalog.RoleCatalogImpl;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.extension.RoleRuntimeOverlayApplier;
import com.habitrain.core.role.snapshot.RoleSnapshotCompiler;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the v2 snapshot layer: {@link RoleSnapshotCompiler} effective-view
 * compilation, {@link RoleSnapshotManager} lobby/round/pending lifecycle, and the
 * catalog's {@code snapshot()}/{@code currentSnapshot()} wiring.
 */
class RoleSnapshotTest {

    private static final ResourceLocation TARGET = ResourceLocation.parse("sre:vigilante");
    private static final int COLOR = 0xFFAA0000;

    private static final RoleSkill.Definition DASH = skillDef(ResourceLocation.parse("mod:dash"));
    private static final RoleSkill.Definition SMOKE = skillDef(ResourceLocation.parse("mod:smoke"));

    @BeforeEach
    void reset() throws Exception {
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "managedRoles", new LinkedHashMap<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "compiledReplacements", new LinkedHashMap<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "patches", new ArrayList<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "replacements", new ArrayList<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "aliases", new ArrayList<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "replacementByTarget", new LinkedHashMap<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "registeredEntryIds", new LinkedHashSet<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "frozen", false);
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "tmmAccessible", false);
        setField(RoleSnapshotManager.class, RoleSnapshotManager.INSTANCE, "lobby", null);
        setField(RoleSnapshotManager.class, RoleSnapshotManager.INSTANCE, "round", null);
        setField(RoleSnapshotManager.class, RoleSnapshotManager.INSTANCE, "pending", null);
        com.habitrain.core.role.snapshot.RoleSnapshotArchive.INSTANCE.clear();
        RoleRuntimeOverlayApplier.clear();
    }

    // ------------------------------------------------------------------
    // RoleSnapshotCompiler
    // ------------------------------------------------------------------

    @Test
    void compileAppliesModifyPatches() {
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(2)).build());

        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        raw.put(TARGET, role(TARGET));
        RoleSnapshot snap = RoleSnapshotCompiler.compile(new RoleSnapshotId(1), raw);

        EffectiveRole er = snap.find(RoleKey.of(TARGET)).orElseThrow();
        // The snapshot compiler is pure: it folds the MODIFY into the immutable
        // profile, materialization onto the live SRERole happens at activation.
        assertEquals(2, er.profile().defaultMax());
        assertEquals(EffectiveRole.Source.MODIFIED, er.source());
    }

    @Test
    void repeatedCompileAfterActivationDoesNotAccumulate() {
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.add(1)).build());

        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        SRERole live = role(TARGET);
        raw.put(TARGET, live);

        RoleSnapshot first = RoleSnapshotCompiler.compile(new RoleSnapshotId(1), raw);
        assertEquals(2, first.find(RoleKey.of(TARGET)).orElseThrow().profile().defaultMax(),
                "1 (pristine) + 1 (ADD) = 2");

        // Activation writes the overlay onto the SAME role object.
        RoleSnapshotManager.INSTANCE.setLobby(first);
        assertEquals(2, live.defaultMaxCount, "overlay materialized onto the live role");

        // Re-compiling from the (now mutated) live object must fold from the
        // captured pristine baseline again: 1 + 1 = 2, never 2 + 1 = 3.
        RoleSnapshot second = RoleSnapshotCompiler.compile(new RoleSnapshotId(2), raw);
        assertEquals(2, second.find(RoleKey.of(TARGET)).orElseThrow().profile().defaultMax(),
                "no patch accumulation across compile -> activate -> compile");

        RoleSnapshot third = RoleSnapshotCompiler.compile(new RoleSnapshotId(3), raw);
        assertEquals(2, third.find(RoleKey.of(TARGET)).orElseThrow().profile().defaultMax(),
                "a third compile still folds from the pristine baseline");
    }

    @Test
    void repeatedCompilePreservesUpstreamSkillsAndRelations() {
        Map<ResourceLocation, List<RoleSkill.Definition>> table = new LinkedHashMap<>();
        table.put(TARGET, List.of(DASH));
        RoleRuntimeOverlayApplier.setSkillBackend(skillBackend(table));

        SRERole guard = new NormalRole(ResourceLocation.parse("sre:guard"), COLOR, false, true,
                SRERole.MoodType.FAKE, 20, true);
        Map<RoleKey, SRERole> resolver = new LinkedHashMap<>();
        resolver.put(RoleKey.of(guard.identifier()), guard);
        RoleRuntimeOverlayApplier.setRelationResolver(resolver::get);

        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET)
                        .skills(RoleSkillPatch.append(RoleSkillSpec.of(SMOKE)))
                        .occupation(RolePatch.RoleKeyListPatch.append(RoleKey.of(guard.identifier())))
                        .build());

        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        SRERole live = role(TARGET);
        raw.put(TARGET, live);

        RoleSnapshot first = RoleSnapshotCompiler.compile(new RoleSnapshotId(1), raw);
        RoleSnapshotManager.INSTANCE.setLobby(first);
        assertEquals(List.of(DASH, SMOKE), table.get(TARGET),
                "APPEND keeps the upstream skill table instead of replacing it");
        assertTrue(live.occupationRoles.contains(guard),
                "APPEND keeps the upstream relations instead of dropping them");

        RoleRuntimeOverlayApplier.restoreAll();
        assertEquals(List.of(DASH), table.get(TARGET), "restore returns the pristine skill table");
        assertTrue(live.occupationRoles.isEmpty(), "restore returns the pristine relations");

        // Compile again from the same (mutated-then-restored) object: the
        // pristine baseline must be reused, not re-captured from live state.
        RoleSnapshot second = RoleSnapshotCompiler.compile(new RoleSnapshotId(2), raw);
        RoleSnapshotManager.INSTANCE.setLobby(second);
        assertEquals(List.of(DASH, SMOKE), table.get(TARGET),
                "recompile still APPENDs onto the pristine table");
        assertTrue(live.occupationRoles.contains(guard),
                "recompile still APPENDs onto the pristine relations");
    }

    @Test
    void compileHidesReplacedTargetAndSurfacesReplacement() {
        RoleDefinition def = definition("habitrain_core", "shadow_killer");
        RoleExtensionRegistry.INSTANCE.replace("habitrain_core",
                RoleReplacement.builder(RoleKey.of(TARGET), def)
                        .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build());
        RoleExtensionRegistry.INSTANCE.freeze();

        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        raw.put(TARGET, role(TARGET));
        RoleSnapshot snap = RoleSnapshotCompiler.compile(new RoleSnapshotId(1), raw);

        ResourceLocation replacementId = ResourceLocation.parse("habitrain_core:shadow_killer");
        assertTrue(snap.isReplaced(TARGET), "replaced target must be hidden");
        assertFalse(snap.isActive(TARGET), "replaced target must not be active");
        assertTrue(snap.isActive(replacementId), "replacement must surface");
        assertEquals(RoleKey.of(replacementId), snap.canonicalize(TARGET));
    }

    @Test
    void compileCapturesAliases() {
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("oldmod", "doctor", "habitrain_core", "plague_doctor"));

        RoleSnapshot snap = RoleSnapshotCompiler.compile(new RoleSnapshotId(1), Map.of());
        assertEquals(RoleKey.of("habitrain_core", "plague_doctor"),
                snap.canonicalize(ResourceLocation.parse("oldmod:doctor")));
    }

    @Test
    void snapshotCanonicalizeFollowsAliasChain() {
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("oldmod", "a", "habitrain_core", "b"));
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("habitrain_core", "b", "habitrain_core", "c"));
        RoleSnapshot snap = RoleSnapshotCompiler.compile(new RoleSnapshotId(1), Map.of());
        assertEquals(RoleKey.of("habitrain_core", "c"),
                snap.canonicalize(ResourceLocation.parse("oldmod:a")));
    }

    // ------------------------------------------------------------------
    // RoleSnapshotManager lifecycle
    // ------------------------------------------------------------------

    @Test
    void managerLifecycleTransitions() {
        RoleSnapshot lobby = new RoleSnapshot(new RoleSnapshotId(1), Map.of(), Map.of(), Set.of());
        RoleSnapshot pending = new RoleSnapshot(new RoleSnapshotId(2), Map.of(), Map.of(), Set.of());

        RoleSnapshotManager.INSTANCE.setLobby(lobby);
        assertEquals(lobby, RoleSnapshotManager.INSTANCE.current(), "no round -> lobby");

        RoleSnapshotManager.INSTANCE.beginRound();
        assertEquals(lobby, RoleSnapshotManager.INSTANCE.current(), "round fixed from lobby");

        RoleSnapshotManager.INSTANCE.queuePending(pending);
        assertEquals(lobby, RoleSnapshotManager.INSTANCE.current(),
                "pending must not affect the live round");

        RoleSnapshotManager.INSTANCE.endRound();
        RoleSnapshotManager.INSTANCE.activatePending();
        assertEquals(pending, RoleSnapshotManager.INSTANCE.current(),
                "pending activates as the new lobby after the round");
        assertNull(RoleSnapshotManager.INSTANCE.pending());
    }

    @Test
    void currentFallsBackToLobbyWhenNoRound() {
        RoleSnapshot lobby = new RoleSnapshot(new RoleSnapshotId(1), Map.of(), Map.of(), Set.of());
        RoleSnapshotManager.INSTANCE.setLobby(lobby);
        assertEquals(lobby, RoleSnapshotManager.INSTANCE.current());
    }

    // ------------------------------------------------------------------
    // Catalog wiring
    // ------------------------------------------------------------------

    @Test
    void catalogSnapshotReturnsManagerId() {
        RoleSnapshot lobby = new RoleSnapshot(new RoleSnapshotId(42), Map.of(), Map.of(), Set.of());
        RoleSnapshotManager.INSTANCE.setLobby(lobby);

        RoleCatalogImpl api = new RoleCatalogImpl(Map.of());
        assertEquals(new RoleSnapshotId(42), api.snapshot());
        assertTrue(api.currentSnapshot().isPresent());
        assertEquals(lobby, api.currentSnapshot().orElseThrow());
    }

    @Test
    void catalogSnapshotFallsBackToEngineVersionWhenNoManagerSnapshot() {
        RoleCatalogImpl api = new RoleCatalogImpl(Map.of());
        assertFalse(api.currentSnapshot().isPresent());
        assertTrue(api.snapshot() != null);
    }

    @Test
    void catalogEffectiveRolesReadsFrozenSnapshotNotLiveRaw() {
        ResourceLocation liveId = ResourceLocation.parse("sre:live");
        ResourceLocation snapId = ResourceLocation.parse("sre:snap");
        SRERole snapRole = role(snapId);
        RoleSnapshot lobby = new RoleSnapshot(
                new RoleSnapshotId(7),
                Map.of(snapId, new EffectiveRole(RoleKey.of(snapId), snapRole, EffectiveRole.Source.BASELINE)),
                Map.of(),
                Set.of());
        RoleSnapshotManager.INSTANCE.setLobby(lobby);

        Map<ResourceLocation, SRERole> live = new LinkedHashMap<>();
        live.put(liveId, role(liveId));
        RoleCatalogImpl api = new RoleCatalogImpl(live);

        assertTrue(api.find(RoleKey.of(snapId)).isPresent(), "catalog must surface the frozen snapshot role");
        assertFalse(api.find(RoleKey.of(liveId)).isPresent(), "live-only roles must not leak while a snapshot is published");
    }

    @Test
    void archiveStoresLobbyAndRestoresCanonical() {
        ResourceLocation snapId = ResourceLocation.parse("sre:snap");
        SRERole snapRole = role(snapId);
        RoleSnapshot lobby = new RoleSnapshot(
                new RoleSnapshotId(9),
                Map.of(snapId, new EffectiveRole(RoleKey.of(snapId), snapRole, EffectiveRole.Source.BASELINE)),
                Map.of(),
                Set.of());
        RoleSnapshotManager.INSTANCE.setLobby(lobby);

        var archived = com.habitrain.core.role.snapshot.RoleSnapshotArchive.INSTANCE.get(new RoleSnapshotId(9));
        assertTrue(archived != null);
        assertEquals(lobby.id(), archived.id());
        assertTrue(com.habitrain.core.role.snapshot.RoleSnapshotArchive.INSTANCE
                .restore(new RoleSnapshotId(9), RoleKey.of(snapId)).isPresent());
        assertTrue(com.habitrain.core.role.diag.RoleDiagnosticsCommands.archive()
                .stream().anyMatch(l -> l.contains("role-snapshot-v9")));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static SRERole role(ResourceLocation id) {
        return new NormalRole(id, COLOR, false, true, SRERole.MoodType.FAKE, 20, true);
    }

    private static RoleDefinition definition(String ns, String path) {
        return RoleDefinition.builder(ns, path)
                .presentation(RolePresentation.builder().color(COLOR).build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder().build())
                .maxSprintTime(20)
                .build();
    }

    private static RoleSkill.Definition skillDef(ResourceLocation id) {
        // Pure record construction — bootstrap-safe (no RoleSkill.skill()).
        return new RoleSkill.Definition(
                id, "skill." + id.getNamespace() + "." + id.getPath(), 0, 1, false, 0, true,
                RoleSkill.AnnounceInfo.none(), false, false, false, true, false,
                ctx -> true);
    }

    private static RoleRuntimeOverlayApplier.SkillBackend skillBackend(
            Map<ResourceLocation, List<RoleSkill.Definition>> table) {
        return new RoleRuntimeOverlayApplier.SkillBackend() {
            @Override
            public List<RoleSkill.Definition> definitions(ResourceLocation roleId) {
                return table.getOrDefault(roleId, List.of());
            }

            @Override
            public void replace(ResourceLocation roleId, List<RoleSkill.Definition> definitions) {
                table.put(roleId, definitions == null ? List.of() : List.copyOf(definitions));
            }
        };
    }

    private static void setField(Class<?> clazz, Object target, String name, Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
