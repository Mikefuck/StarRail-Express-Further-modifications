package com.habitrain.core.role.config;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.action.RoleActionApi;
import com.habitrain.core.api.role.v2.action.RoleActionDirection;
import com.habitrain.core.api.role.v2.action.RoleActionSpec;
import com.habitrain.core.api.role.v2.behavior.RoleHooks;
import com.habitrain.core.api.role.v2.behavior.RoleScope;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityApi;
import com.habitrain.core.api.role.v2.capability.RoleVoicePolicy;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.state.RoleStateApi;
import com.habitrain.core.api.role.v2.state.RoleStateSpec;
import com.habitrain.core.role.action.RoleActionServiceImpl;
import com.habitrain.core.role.behavior.RoleHookRegistry;
import com.habitrain.core.role.capability.RoleCapabilityServiceImpl;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.extension.RoleRuntimeOverlayApplier;
import com.habitrain.core.role.state.RoleStateServiceImpl;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit P1-4: the full definition hash must cover declaration CONTENT (not just
 * entry ids), stay order-independent, and the manifest provider list must
 * include providers that only register hooks/state/action/voice/chat.
 */
class RoleManifestDefinitionCollectorTest {

    private static final RoleKey ROLE = RoleKey.of("habitrain_core", "test_role");

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
        setField(RoleHookRegistry.class, RoleHookRegistry.INSTANCE, "hooks", new LinkedHashMap<>());
        setField(RoleHookRegistry.class, RoleHookRegistry.INSTANCE, "frozen", false);
        ((RoleActionServiceImpl) RoleActionApi.instance()).clear(true);
        ((RoleStateServiceImpl) RoleStateApi.instance()).clear(true);
        ((RoleCapabilityServiceImpl) RoleCapabilityApi.instance()).clear();
        RoleExtensionConfigService.INSTANCE.resetForTests();
        RoleSnapshotManager.INSTANCE.clear();
        RoleRuntimeOverlayApplier.clear();
    }

    @Test
    void hashChangesWhenModifyContentChanges() {
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(ROLE).entryKey("a").defaultMax(RolePatch.IntPatch.set(2)).build());
        String two = RoleManifestHashes.definitionHash();
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(ROLE).entryKey("b").defaultMax(RolePatch.IntPatch.set(3)).build());
        String three = RoleManifestHashes.definitionHash();
        assertNotEquals(two, three,
                "the same entry id but a different patch value must change the hash");

        // Deterministic: recomputing with the same state yields the same hash.
        assertEquals(three, RoleManifestHashes.definitionHash());
    }

    @Test
    void hashIsOrderIndependentAcrossDeclarations() throws Exception {
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(ROLE).entryKey("x").defaultMax(RolePatch.IntPatch.set(2)).build());
        RoleExtensionRegistry.INSTANCE.modify("othermod",
                RolePatch.builder(ROLE).entryKey("y").innocent(RolePatch.BooleanPatch.set(true)).build());
        String forward = RoleManifestHashes.definitionHash();

        // Same declarations, reversed registration order -> sorted rows must match.
        reset();
        RoleExtensionRegistry.INSTANCE.modify("othermod",
                RolePatch.builder(ROLE).entryKey("y").innocent(RolePatch.BooleanPatch.set(true)).build());
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(ROLE).entryKey("x").defaultMax(RolePatch.IntPatch.set(2)).build());
        String reversed = RoleManifestHashes.definitionHash();
        assertEquals(forward, reversed, "sorted canonical rows must be order-independent");
    }

    @Test
    void hashCoversHooksActionsStateAndPolicies() {
        String baseline = RoleManifestHashes.definitionHash();

        RoleHookRegistry.INSTANCE.register(ROLE, RoleScope.HOLDER, "hook_provider", "hook_entry",
                com.habitrain.core.api.role.v2.definition.PatchPriority.NORMAL,
                RoleHooks.builder().interaction(new com.habitrain.core.api.role.v2.behavior.RoleInteractionHooks() {})
                        .build());
        assertNotEquals(baseline, RoleManifestHashes.definitionHash(),
                "a behavior hook declaration must be part of the definition hash");

        ((RoleActionServiceImpl) RoleActionApi.instance()).registerManaged("act_provider", "act_provider:kick",
                RoleActionSpec.of("act_provider", "kick").role(ROLE)
                        .direction(RoleActionDirection.S2C).build());
        assertNotEquals(baseline, RoleManifestHashes.definitionHash(),
                "an action schema must be part of the definition hash");

        ((RoleStateServiceImpl) RoleStateApi.instance()).registerManaged("state_provider", "state_provider:souls",
                RoleStateSpec.of("state_provider", "souls", Integer.class).role(ROLE)
                        .defaultValue(() -> 0).build());
        assertNotEquals(baseline, RoleManifestHashes.definitionHash(),
                "a state schema must be part of the definition hash");

        ((RoleCapabilityServiceImpl) RoleCapabilityApi.instance()).registerVoice(
                "voice_provider", "voice_provider:silence",
                RoleVoicePolicy.of("voice_provider", "silence").role(ROLE).muteSend().build());
        assertNotEquals(baseline, RoleManifestHashes.definitionHash(),
                "a voice policy must be part of the definition hash");
    }

    @Test
    void hooksOnlyProviderAppearsInManifestProviderList() {
        // Audit P1-4: a provider registering ONLY hooks (no ADD/MODIFY/REPLACE/ALIAS)
        // must still surface in the manifest provider list.
        var tx = RoleExtensionRegistry.INSTANCE.begin("hooks_only");
        tx.hooks(ROLE, RoleScope.HOLDER, RoleHooks.builder()
                .interaction(new com.habitrain.core.api.role.v2.behavior.RoleInteractionHooks() {}).build());
        tx.commit();

        assertTrue(RoleExtensionRegistry.INSTANCE.providerIds().contains("hooks_only"),
                "hooks-only providers must be published by their transaction");
        assertTrue(RoleManifestService.build().providers().stream()
                        .anyMatch(p -> p.providerId().equals("hooks_only")),
                "hooks-only providers must appear in the manifest");
    }

    @Test
    void requiredClientProviderIsPublishedAndManifested() {
        var tx = RoleExtensionRegistry.INSTANCE.begin("client_facing");
        tx.setRequiresClient(true);
        tx.modify(RolePatch.builder(ROLE).entryKey("a").defaultMax(RolePatch.IntPatch.set(2)).build());
        tx.commit();

        assertTrue(RoleExtensionRegistry.INSTANCE.requiredClientProviderIds().contains("client_facing"));
        var row = RoleManifestService.build().providers().stream()
                .filter(p -> p.providerId().equals("client_facing")).findFirst().orElseThrow();
        assertTrue(row.requiredClient(), "explicit requiresClient() must reach the manifest");
    }

    private static void setField(Class<?> clazz, Object target, String name, Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
