package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.definition.*;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.extension.RoleRuntimeOverlayApplier;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;

/** Bootstrap-free v2 registry fixtures for catalog queries. */
final class RoleCatalogTestFixtures {
    private RoleCatalogTestFixtures() {}

    static void reset() throws Exception {
        RoleExtensionConfigService.INSTANCE.resetForTests();
        RoleRuntimeOverlayApplier.clear();
        RoleExtensionRegistry registry = RoleExtensionRegistry.INSTANCE;
        for (String name : new String[]{"managedRoles", "addedDefinitions", "compiledReplacements",
                "replacementCompileCache", "patches", "replacements", "aliases", "replacementByTarget",
                "aliasSourceOwners", "aliasSourcesByFrom", "registeredEntryIds", "providers", "requiredClientProviders"}) {
            Field field = RoleExtensionRegistry.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(registry);
            if (value instanceof Map<?, ?> map) map.clear();
            else if (value instanceof Collection<?> collection) collection.clear();
        }
        for (String name : new String[]{"frozen", "tmmAccessible"}) {
            Field field = RoleExtensionRegistry.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(registry, false);
        }
        Field entries = RoleExtensionRegistry.class.getDeclaredField("compiledEntries");
        entries.setAccessible(true);
        entries.set(registry, java.util.List.of());
    }

    static void replace(ResourceLocation target, SRERole replacement) {
        RoleDefinition definition = RoleDefinition.builder(RoleKey.of(replacement.identifier()))
                .presentation(RolePresentation.builder().color(replacement.getColor()).build())
                .faction(RoleFactionProfile.builder().killer().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder().build())
                .maxSprintTime(20).build();
        RoleExtensionRegistry.INSTANCE.replace(replacement.identifier().getNamespace(),
                RoleReplacement.builder(RoleKey.of(target), definition)
                        .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build());
        RoleExtensionRegistry.INSTANCE.freeze();
    }
}
