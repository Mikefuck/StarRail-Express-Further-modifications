package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.ReplaceRoleDefinition;
import com.habitrain.core.role.catalog.RoleCatalogImpl;
import com.habitrain.core.role.override.EffectiveSnapshot;
import com.habitrain.core.role.override.RoleOverrideEngine;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the v2 {@link RoleCatalogApi} logic via a {@link RoleCatalogImpl}
 * backed by an injected raw-role map and a reflection-seeded engine snapshot.
 *
 * <p>{@code RoleOverrideRegistry#registerReplace} validates the provider mod
 * through FabricLoader and cannot run outside a launched game, so the definition
 * is built through the builder only (which does not touch FabricLoader) and the
 * engine snapshot is injected directly. Injecting the raw role map also avoids
 * {@code TMMRoles}'s static initializer, so no Minecraft bootstrap is required.
 */
class RoleCatalogApiTest {

    private static final ResourceLocation TARGET_ID = ResourceLocation.parse("sre:killer");
    private static final ResourceLocation REPLACEMENT_ID =
            ResourceLocation.parse("habitrain_core:shadow_killer");

    @BeforeEach
    @AfterEach
    void resetEngineSnapshot() throws Exception {
        RoleSnapshotManager.INSTANCE.clear();
        setSnapshot(new EffectiveSnapshot(Map.of(), Map.of(), List.of()));
    }

    // ------------------------------------------------------------------
    // passthrough: a plain (non-replaced) role is surfaced as itself
    // ------------------------------------------------------------------

    @Test
    void plainRolesAreSurfacedAndResolvable() throws Exception {
        resetEngineSnapshot();

        SRERole civilian = role(TARGET_ID);
        Map<ResourceLocation, SRERole> raw = new HashMap<>();
        raw.put(TARGET_ID, civilian);
        RoleCatalogImpl api = new RoleCatalogImpl(raw);

        RoleKey key = RoleKey.of(TARGET_ID);
        assertTrue(effectiveRoles(api).stream().anyMatch(er -> er.key().equals(key)),
                "effective roles should include a plain baseline role");

        assertEquals(key, api.canonicalize(TARGET_ID));

        EffectiveRole found = api.find(key).orElseThrow();
        assertEquals(key, found.key());
        assertEquals(civilian, found.role());

        EffectiveRole resolved = api.resolve(civilian).orElseThrow();
        assertEquals(civilian, resolved.role());

        assertTrue(api.isActive(key));
        assertFalse(api.isReplaced(key));
        assertFalse(api.isModified(key));
        assertFalse(api.isAdded(key));
    }

    @Test
    void snapshotIdIsNonNullAndStableAcrossReads() {
        RoleCatalogImpl api = new RoleCatalogImpl(Map.of());
        RoleSnapshotId first = api.snapshot();
        RoleSnapshotId second = api.snapshot();
        assertNotNull(first);
        assertEquals(first, second);
    }

    // ------------------------------------------------------------------
    // replacement: the hidden target must not leak, the replacement must
    // resolve from the target key, the stored id and the raw role
    // ------------------------------------------------------------------

    @Test
    void replacedTargetDoesNotLeakAndResolvesToReplacement() throws Exception {
        SRERole target = role(TARGET_ID);
        SRERole replacement = role(REPLACEMENT_ID);
        Map<ResourceLocation, SRERole> raw = new HashMap<>();
        raw.put(TARGET_ID, target);

        ReplaceRoleDefinition def = ReplaceRoleDefinition.builder()
                .sourceModId("habitrain_core")
                .displayName(Component.literal("影杀"))
                .targetRoleId(TARGET_ID)
                .replacementRole(replacement)
                .build();
        setSnapshot(new EffectiveSnapshot(Map.of(TARGET_ID, def), Map.of(), List.of()));

        RoleCatalogImpl api = new RoleCatalogImpl(raw);

        // Leakage: the hidden target is absent, the replacement is present once.
        Collection<EffectiveRole> all = effectiveRoles(api);
        assertFalse(all.stream().anyMatch(er -> er.key().location().equals(TARGET_ID)),
                "replaced target must not leak into effective roles");
        assertTrue(all.stream().anyMatch(er -> er.key().location().equals(REPLACEMENT_ID)),
                "active replacement must be present in effective roles");

        // Canonicalization redirects the target id to the replacement id.
        assertEquals(RoleKey.of(REPLACEMENT_ID), api.canonicalize(TARGET_ID));

        // find(target key) returns the replacement.
        EffectiveRole found = api.find(RoleKey.of(TARGET_ID)).orElseThrow();
        assertEquals(REPLACEMENT_ID, found.id());
        assertEquals(REPLACEMENT_ID, found.role().identifier());

        // Legacy stored path ("killer") resolves to the replacement.
        EffectiveRole stored = api.resolveStored(TARGET_ID.getPath()).orElseThrow();
        assertEquals(REPLACEMENT_ID, stored.id());

        // Raw target role resolves to the replacement and is visible.
        EffectiveRole rawResolved = api.resolve(target).orElseThrow();
        assertEquals(REPLACEMENT_ID, rawResolved.id());

        assertTrue(api.isReplaced(RoleKey.of(TARGET_ID)));
        // The target is live under the replacement identity.
        assertTrue(api.isActive(RoleKey.of(TARGET_ID)));
        assertFalse(api.isModified(RoleKey.of(TARGET_ID)));
        assertFalse(api.isAdded(RoleKey.of(TARGET_ID)));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Collection<EffectiveRole> effectiveRoles(RoleCatalogImpl api) {
        return api.effectiveRoles();
    }

    private static void setSnapshot(EffectiveSnapshot snap) throws Exception {
        Field field = RoleOverrideEngine.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        field.set(RoleOverrideEngine.getInstance(), snap);
    }

    private static SRERole role(ResourceLocation id) {
        return new NormalRole(id, 0xFFAA0000, false, true, SRERole.MoodType.FAKE, 20, true);
    }
}
