package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.definition.ReplacementIdentity;
import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.role.extension.ProviderRegistrationTransaction;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.extension.RoleRuntimeOverlayApplier;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B: provider-scoped registration transactions. A provider's declarations
 * are staged and validated inside a transaction; nothing is written to the
 * registry until {@code commit()}, so a throwing provider rolls back with zero
 * entries. {@code commit()} touches the upstream {@code TMMRoles} registry and is
 * therefore runtime-only; these tests pin the staging/rollback guarantees and the
 * strict ADD-ownership rule.
 */
class ProviderRegistrationTransactionTest {

    private static final ResourceLocation TARGET = ResourceLocation.parse("sre:vigilante");

    @BeforeEach
    void resetRegistry() throws Exception {
        setField("managedRoles", new LinkedHashMap<>());
        setField("compiledReplacements", new LinkedHashMap<>());
        setField("patches", new ArrayList<>());
        setField("replacements", new ArrayList<>());
        setField("aliases", new ArrayList<>());
        setField("replacementByTarget", new LinkedHashMap<>());
        setField("registeredEntryIds", new LinkedHashSet<>());
        setField("frozen", false);
        setField("tmmAccessible", false);
        RoleSnapshotManager.INSTANCE.clear();
        RoleRuntimeOverlayApplier.clear();
    }

    @Test
    void providerAddNamespaceMustEqualProviderId() {
        ProviderRegistrationTransaction tx = RoleExtensionRegistry.INSTANCE.begin("habitrain_core");
        RoleDefinition foreign = definition("othermod", "intruder");
        assertThrows(IllegalArgumentException.class, () -> tx.add(foreign),
                "ADD role id must live in the provider's namespace");
        // Nothing was staged; the transaction can still accept a legal declaration.
        tx.add(definition("habitrain_core", "own_role"));
        tx.rollback();
    }

    @Test
    void providerTransactionRollsBackOnThrowingProvider() {
        ProviderRegistrationTransaction tx = RoleExtensionRegistry.INSTANCE.begin("habitrain_core");
        tx.add(definition("habitrain_core", "one"));
        tx.add(definition("habitrain_core", "two"));
        tx.rollback();

        assertTrue(RoleExtensionRegistry.INSTANCE.getManagedRoles().isEmpty(),
                "rollback must leave zero registered ADD roles");

        // A second transaction can re-stage the same ids: nothing leaked.
        ProviderRegistrationTransaction again = RoleExtensionRegistry.INSTANCE.begin("habitrain_core");
        again.add(definition("habitrain_core", "one"));
        again.add(definition("habitrain_core", "two"));
        again.rollback();
    }

    @Test
    void providerTransactionStagesModifyDuplicateEntryId() {
        ProviderRegistrationTransaction tx = RoleExtensionRegistry.INSTANCE.begin("habitrain_core");
        tx.modify(RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(2)).build());
        assertThrows(IllegalArgumentException.class,
                () -> tx.modify(RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(3)).build()),
                "a provider may not declare the same MODIFY entryId twice");
        tx.rollback();
    }

    @Test
    void providerTransactionStagesMultipleReplacementCandidates() throws Exception {
        // Seed a known ADD role so TARGET is not dangling-INVALID.
        setField("managedRoles", new LinkedHashMap<>(Map.of(TARGET,
                com.habitrain.core.role.extension.ManagedSRERole.from(definition("sre", "vigilante")))));
        ProviderRegistrationTransaction tx = RoleExtensionRegistry.INSTANCE.begin("habitrain_core");
        tx.replace(RoleReplacement.builder(RoleKey.of(TARGET), definition("sre", "vigilante"))
                .entryKey("a").identity(ReplacementIdentity.PRESERVE_TARGET_ID).build());
        // Audit P2-1: a second candidate for the same target is staged (with a
        // distinct entryKey), never rejected — both resolve to CONFLICT later.
        tx.replace(RoleReplacement.builder(RoleKey.of(TARGET), definition("sre", "vigilante"))
                .entryKey("b").identity(ReplacementIdentity.PRESERVE_TARGET_ID).build());
        tx.commit();

        assertEquals(2, RoleExtensionRegistry.INSTANCE.v2Entries().stream()
                .filter(e -> e.operation() == com.habitrain.core.role.extension.RoleOperation.REPLACE).count(),
                "both replacement candidates stay registered");
        assertEquals(2, RoleExtensionRegistry.INSTANCE.v2Entries().stream()
                .filter(e -> e.operation() == com.habitrain.core.role.extension.RoleOperation.REPLACE)
                .filter(e -> e.status() == com.habitrain.core.role.extension.EntryStatus.CONFLICT).count(),
                "an unresolved multi-candidate target reports CONFLICT on every candidate");
        assertFalse(RoleExtensionRegistry.INSTANCE.isReplaced(TARGET),
                "no candidate activates while the conflict is unresolved");
        tx.rollback(); // closed, no-op
    }

    @Test
    void transactionIsClosedAfterRollback() {
        ProviderRegistrationTransaction tx = RoleExtensionRegistry.INSTANCE.begin("habitrain_core");
        tx.rollback();
        assertThrows(IllegalStateException.class,
                () -> tx.add(definition("habitrain_core", "late")),
                "staging after rollback must be rejected");
    }

    private static RoleDefinition definition(String ns, String path) {
        return RoleDefinition.builder(ns, path)
                .presentation(RolePresentation.builder().color(0xFFAA0000).build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder().build())
                .maxSprintTime(20)
                .build();
    }

    private static void setField(String name, Object value) throws Exception {
        Field field = RoleExtensionRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(RoleExtensionRegistry.INSTANCE, value);
    }
}
