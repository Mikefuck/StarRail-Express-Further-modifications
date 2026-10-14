package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.definition.ReplacementIdentity;
import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.api.role.v2.skill.RoleSkillSpec;
import com.habitrain.core.game.sre.role.SreRoleResolver;
import com.habitrain.core.role.catalog.RoleCatalogImpl;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Uses real upstream role/skill tables, without ever starting a Minecraft server. */
class ReplacementRoleBootstrapTest {
    private static final String PROVIDER = "replacement_bootstrap_test";
    private static final ResourceLocation TARGET = ResourceLocation.parse("upstream:remote_original");
    private static final ResourceLocation REPLACEMENT = ResourceLocation.parse(PROVIDER + ":remote_role");
    private final RoleExtensionRegistry registry = RoleExtensionRegistry.INSTANCE;
    private RoleExtensionRegistry.RegistrationSnapshot before;
    @TempDir
    static Path configDirectory;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void bootstrapGameRegistries() throws ReflectiveOperationException {
        // SRE's role-table initializer reads its AutoConfig files even without a game launch.
        var loader = FabricLoader.getInstance();
        var configDir = loader.getClass().getDeclaredField("configDir");
        configDir.setAccessible(true);
        configDir.set(loader, configDirectory);
        var metadata = new BuiltinModMetadata.Builder("starrailexpress", "4.3.0").build();
        var container = (ModContainer) Proxy.newProxyInstance(ModContainer.class.getClassLoader(),
                new Class<?>[]{ModContainer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getMetadata")) return metadata;
                    throw new AssertionError("Unexpected loader query: " + method.getName());
                });
        var modMap = loader.getClass().getDeclaredField("modMap");
        modMap.setAccessible(true);
        ((Map<String, Object>) modMap.get(loader)).put("starrailexpress", container);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        before = registry.snapshotForTransaction(true);
        RoleExtensionConfigService.INSTANCE.resetForTests();
        RoleSnapshotManager.INSTANCE.clear();
        TMMRoles.registerRole(new NormalRole(TARGET, 0xFF6464C8,
                false, true, SRERole.MoodType.FAKE, 20, true));
    }

    @AfterEach
    void tearDown() {
        RoleSkill.unregister(TARGET);
        RoleSkill.unregister(REPLACEMENT);
        registry.restoreTransactionSnapshot(before);
        RoleExtensionConfigService.INSTANCE.resetForTests();
        RoleSnapshotManager.INSTANCE.clear();
    }

    @Test
    void newIdReplacementAndSkillsResolveOnRemoteClientBeforeServerFreeze() {
        assertRemoteClientPublication(ReplacementIdentity.NEW_ID_WITH_ALIAS, REPLACEMENT);
    }

    @Test
    void preservedIdReplacementAndSkillsResolveWithoutAnyPriorAdd() {
        assertRemoteClientPublication(ReplacementIdentity.KEEP_CANONICAL_ID, TARGET);
    }

    @Test
    void conflictingReplacementsRemainInactiveDuringCommonInitialization() {
        RoleReplacement first = replacement(ReplacementIdentity.NEW_ID_WITH_ALIAS, REPLACEMENT);
        RoleReplacement second = RoleReplacement.builder(RoleKey.of(TARGET), first.replacement())
                .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).entryKey("second").build();
        registry.replace(PROVIDER, first);
        registry.replace(PROVIDER, second);

        registry.registerDeclaredSkills();

        assertNull(registry.compiledReplacement(first));
        assertNull(TMMRoles.getRole(REPLACEMENT));
        assertTrue(RoleSkill.getDefinitions(REPLACEMENT).isEmpty());
        assertSame(TMMRoles.getRole(TARGET), SreRoleResolver.resolveStoredId(TARGET.toString()));
    }

    private void assertRemoteClientPublication(ReplacementIdentity identity, ResourceLocation id) {
        RoleReplacement declaration = replacement(identity, id);
        registry.replace(PROVIDER, declaration);

        // This is the publication path called after all common provider transactions.
        // Remote clients never invoke SERVER_STARTED / registry.freeze().
        registry.registerDeclaredSkills();

        ManagedSRERole live = registry.compiledReplacement(declaration);
        assertNotNull(live, "Common initialization must compile REPLACE roles before a server starts");
        if (identity == ReplacementIdentity.NEW_ID_WITH_ALIAS) {
            assertSame(live, TMMRoles.getRole(id), "CCA resolves synchronized role IDs through the upstream table");
        }
        var catalog = new RoleCatalogImpl(TMMRoles.ROLES);
        assertSame(live, catalog.find(RoleKey.of(TARGET)).orElseThrow().role());
        assertSame(live, catalog.find(RoleKey.of(id)).orElseThrow().role());
        assertSame(live, SreRoleResolver.resolveStoredId(TARGET.toString()));
        assertSame(live, SreRoleResolver.resolveStoredId(id.toString()));
        assertSame(live, SreRoleResolver.resolveStoredId(TARGET.getPath()));
        var skill = declaration.replacement().skills().getFirst().definition();
        assertEquals(List.of(skill), RoleSkill.getDefinitions(live));
        assertEquals(id, RoleSkill.getSkillEntry(skill.id()).orElseThrow().roleId());

        registry.registerDeclaredSkills();
        assertSame(live, registry.compiledReplacement(declaration));
        assertEquals(List.of(skill), RoleSkill.getDefinitions(live));
        // Preparing client handles must not close the common registration phase.
        registry.begin(PROVIDER).rollback();
    }

    private static RoleReplacement replacement(ReplacementIdentity identity, ResourceLocation id) {
        var skill = RoleSkill.skill(ResourceLocation.parse(PROVIDER + ":remote_skill"),
                        "test.remote_skill", ctx -> true).showOnHud(true).build();
        var definition = RoleDefinition.builder(RoleKey.of(id))
                .presentation(RolePresentation.builder().build())
                .faction(RoleFactionProfile.builder().killer().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder().build())
                .maxSprintTime(20).skill(RoleSkillSpec.of(skill)).build();
        return RoleReplacement.builder(RoleKey.of(TARGET), definition)
                .identity(identity).entryKey("remote_replace").build();
    }
}
