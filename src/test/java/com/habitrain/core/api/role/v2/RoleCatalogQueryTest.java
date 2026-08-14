package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.ReplaceRoleDefinition;
import com.habitrain.core.role.catalog.RoleCatalogImpl;
import com.habitrain.core.role.override.EffectiveSnapshot;
import com.habitrain.core.role.override.RoleOverrideEngine;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import io.wifi.starrailexpress.api.GameMode;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link RoleQuery} filtering in {@link RoleCatalogImpl}, using the
 * same injected-raw-map + reflection-seeded-snapshot harness as
 * {@link RoleCatalogApiTest}.
 */
class RoleCatalogQueryTest {

    private static final ResourceLocation CIV_ID = ResourceLocation.parse("sre:civilian");
    private static final ResourceLocation KILLER_ID = ResourceLocation.parse("sre:killer");
    private static final ResourceLocation NEUTRAL_ID = ResourceLocation.parse("sre:neutral");
    private static final ResourceLocation TARGET_ID = ResourceLocation.parse("sre:old_mafia");
    private static final ResourceLocation REPLACEMENT_ID =
            ResourceLocation.parse("habitrain_core:new_mafia");

    @BeforeEach
    @AfterEach
    void resetEngineSnapshot() throws Exception {
        RoleSnapshotManager.INSTANCE.clear();
        setSnapshot(new EffectiveSnapshot(Map.of(), Map.of(), List.of()));
    }

    // ------------------------------------------------------------------
    // generic == no-arg; side accepted
    // ------------------------------------------------------------------

    @Test
    void genericQueryMatchesNoArgResult() {
        RoleCatalogImpl api = new RoleCatalogImpl(rawWith(CIV_ID, killer(KILLER_ID)));
        assertEquals(
                ids(api.effectiveRoles()),
                ids(api.effectiveRoles(RoleQuery.generic())));
    }

    @Test
    void everySideAcceptsTheSharedSet() {
        RoleCatalogImpl api = new RoleCatalogImpl(rawWith(CIV_ID, civilian(CIV_ID)));
        int expected = ids(api.effectiveRoles()).size();
        assertEquals(expected, ids(api.effectiveRoles(
                RoleQuery.builder().side(QuerySide.ANY).build())).size());
        assertEquals(expected, ids(api.effectiveRoles(
                RoleQuery.builder().side(QuerySide.PHYSICAL).build())).size());
        assertEquals(expected, ids(api.effectiveRoles(
                RoleQuery.builder().side(QuerySide.LOGICAL).build())).size());
    }

    // ------------------------------------------------------------------
    // faction
    // ------------------------------------------------------------------

    @Test
    void factionFilterReturnsOnlyMatchingFactions() {
        RoleCatalogImpl api = new RoleCatalogImpl(rawWith(
                CIV_ID, civilian(CIV_ID), KILLER_ID, killer(KILLER_ID),
                NEUTRAL_ID, neutral(NEUTRAL_ID)));

        assertEquals(List.of(CIV_ID.toString()), ids(api.effectiveRoles(
                RoleQuery.builder().factions(RoleFaction.INNOCENT).build())));
        assertEquals(List.of(KILLER_ID.toString()), ids(api.effectiveRoles(
                RoleQuery.builder().factions(RoleFaction.KILLER).build())));
        assertEquals(List.of(NEUTRAL_ID.toString()), ids(api.effectiveRoles(
                RoleQuery.builder().factions(RoleFaction.NEUTRAL).build())));
    }

    // ------------------------------------------------------------------
    // map ability
    // ------------------------------------------------------------------

    @Test
    void mapAbilityFilterTreatsAllRolesAsEverywhere() {
        SRERole allMap = civilian(CIV_ID).setSpecialMapRole(SRERole.SpecialMapRoleMap.ALL);
        SRERole trap = civilian(NEUTRAL_ID).setSpecialMapRole(SRERole.SpecialMapRoleMap.TRAP);
        RoleCatalogImpl api = new RoleCatalogImpl(rawWith(CIV_ID, allMap, NEUTRAL_ID, trap));

        assertEquals(List.of(CIV_ID.toString(), NEUTRAL_ID.toString()), ids(
                api.effectiveRoles(
                        RoleQuery.builder().mapAbilities(SRERole.SpecialMapRoleMap.TRAP).build())),
                "ALL-map roles qualify on every requested map");

        assertEquals(List.of(CIV_ID.toString()), ids(
                api.effectiveRoles(
                        RoleQuery.builder().mapAbilities(SRERole.SpecialMapRoleMap.HORSE).build())),
                "a role restricted to an unrelated map is excluded");
    }

    // ------------------------------------------------------------------
    // tags (all must match)
    // ------------------------------------------------------------------

    @Test
    void tagsFilterRequiresAllTags() {
        SRERole flagged = civilian(CIV_ID).addFlag("a", "b");
        RoleCatalogImpl api = new RoleCatalogImpl(rawWith(CIV_ID, flagged));

        assertTrue(api.effectiveRoles(
                RoleQuery.builder().tags("a", "b").build()).stream()
                .anyMatch(er -> er.id().equals(CIV_ID)),
                "role carrying all requested tags must match");
        assertFalse(api.effectiveRoles(
                RoleQuery.builder().tags("a", "c").build()).stream()
                .anyMatch(er -> er.id().equals(CIV_ID)),
                "role missing one requested tag must be excluded");
    }

    // ------------------------------------------------------------------
    // provider namespace
    // ------------------------------------------------------------------

    @Test
    void providerFilterRestrictsByNamespace() {
        SRERole coreRole = civilian(ResourceLocation.parse("habitrain_core:mike"));
        RoleCatalogImpl api = new RoleCatalogImpl(rawWith(CIV_ID, civilian(CIV_ID),
                ResourceLocation.parse("habitrain_core:mike"), coreRole));

        assertEquals(List.of("habitrain_core:mike"), ids(
                api.effectiveRoles(RoleQuery.builder().provider("habitrain_core").build())));
    }

    // ------------------------------------------------------------------
    // player count window
    // ------------------------------------------------------------------

    @Test
    void playerCountFilterHonoursSpawnWindow() {
        SRERole needsTen = civilian(CIV_ID);
        needsTen.defaultEnableNeedPlayerCount = 10;
        SRERole capped = killer(KILLER_ID);
        capped.defaultEnableMaxPlayerCount = 8;
        RoleCatalogImpl api = new RoleCatalogImpl(rawWith(CIV_ID, needsTen, KILLER_ID, capped));

        Collection<EffectiveRole> atEight = api.effectiveRoles(
                RoleQuery.builder().playerCount(8).build());
        assertTrue(ids(atEight).contains(KILLER_ID.toString()),
                "role capped at 8 players stays at 8");
        assertFalse(ids(atEight).contains(CIV_ID.toString()),
                "role needing 10 players is excluded at 8");

        Collection<EffectiveRole> atTwelve = api.effectiveRoles(
                RoleQuery.builder().playerCount(12).build());
        assertTrue(ids(atTwelve).contains(CIV_ID.toString()));
        assertFalse(ids(atTwelve).contains(KILLER_ID.toString()),
                "role capped at 8 players is excluded at 12");
    }

    // ------------------------------------------------------------------
    // purpose: RANDOM honours canBeRandomed
    // ------------------------------------------------------------------

    @Test
    void randomPurposeExcludesNonRandomableRoles() {
        SRERole notRandomable = civilian(CIV_ID).setCanBeRandomedByOtherRoles(false);
        SRERole randomable = killer(KILLER_ID).setCanBeRandomedByOtherRoles(true);
        RoleCatalogImpl api = new RoleCatalogImpl(rawWith(CIV_ID, notRandomable, KILLER_ID, randomable));

        Collection<EffectiveRole> randomPool = api.effectiveRoles(
                RoleQuery.builder().purpose(QueryPurpose.RANDOM).build());
        assertTrue(ids(randomPool).contains(KILLER_ID.toString()));
        assertFalse(ids(randomPool).contains(CIV_ID.toString()));
    }

    // ------------------------------------------------------------------
    // includeReplaced surfaces hidden baselines
    // ------------------------------------------------------------------

    @Test
    void includeReplacedSurfacesHiddenTargetBaseline() throws Exception {
        SRERole target = civilian(TARGET_ID);
        SRERole replacement = killer(REPLACEMENT_ID);
        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        raw.put(TARGET_ID, target);

        ReplaceRoleDefinition def = ReplaceRoleDefinition.builder()
                .sourceModId("habitrain_core")
                .displayName(Component.literal("新黑手党"))
                .targetRoleId(TARGET_ID)
                .replacementRole(replacement)
                .build();
        setSnapshot(new EffectiveSnapshot(Map.of(TARGET_ID, def), Map.of(), List.of()));

        RoleCatalogImpl api = new RoleCatalogImpl(raw);

        Collection<EffectiveRole> hidden = api.effectiveRoles(
                RoleQuery.builder().includeReplaced(true).build());
        assertEquals(List.of(REPLACEMENT_ID.toString(), TARGET_ID.toString()), ids(hidden),
                "the visible replacement comes first, then the surfaced hidden baseline");

        Collection<EffectiveRole> visible = api.effectiveRoles(RoleQuery.generic());
        assertEquals(List.of(REPLACEMENT_ID.toString()), ids(visible),
                "the hidden target must not leak by default");
    }

    // ------------------------------------------------------------------
    // mode excludes other-mode roles except for rotation / generic
    // ------------------------------------------------------------------

    @Test
    void modeExcludesOtherModeRolesForGameplayPurposes() {
        GameMode mode = new GameMode(ResourceLocation.parse("sre:test_mode"), 100, 4) {
            @Override
            public void initializeGame(ServerLevel serverWorld,
                                       SREGameWorldComponent gameWorldComponent,
                                       List<ServerPlayer> players) {
            }

            @Override
            public boolean canHaveMeeting() {
                return false;
            }
        };
        SRERole otherMode = civilian(NEUTRAL_ID).setOtherModeRole(true);
        SRERole normal = civilian(CIV_ID);
        RoleCatalogImpl api = new RoleCatalogImpl(rawWith(CIV_ID, normal, NEUTRAL_ID, otherMode));

        Collection<EffectiveRole> asRandom = api.effectiveRoles(
                RoleQuery.builder().mode(mode).purpose(QueryPurpose.RANDOM).build());
        assertTrue(ids(asRandom).contains(CIV_ID.toString()));
        assertFalse(ids(asRandom).contains(NEUTRAL_ID.toString()),
                "gameplay purpose with a concrete mode excludes other-mode roles");

        Collection<EffectiveRole> asRotation = api.effectiveRoles(
                RoleQuery.builder().mode(mode).purpose(QueryPurpose.ROTATION).build());
        assertTrue(ids(asRotation).contains(NEUTRAL_ID.toString()),
                "rotation keeps other-mode roles");

        Collection<EffectiveRole> asGeneric = api.effectiveRoles(
                RoleQuery.builder().mode(mode).purpose(QueryPurpose.GENERIC).build());
        assertTrue(ids(asGeneric).contains(NEUTRAL_ID.toString()),
                "generic exploratory query never excludes other-mode roles");
    }

    @Test
    void includeDisabledAndInvalidAreAcceptedContractFlags() {
        RoleQuery query = RoleQuery.builder()
                .includeDisabled(true)
                .includeInvalid(true)
                .build();
        assertTrue(query.includeDisabled());
        assertTrue(query.includeInvalid());
        RoleCatalogImpl api = new RoleCatalogImpl(rawWith(CIV_ID, civilian(CIV_ID)));
        assertEquals(ids(api.effectiveRoles()), ids(api.effectiveRoles(query)),
                "reserved status flags must not drop live roles until catalog rows carry status");
    }

    // ------------------------------------------------------------------
    // ordering
    // ------------------------------------------------------------------

    @Test
    void orderingSortsByIdAndName() {
        SRERole zebra = civilian(ResourceLocation.parse("sre:zebra"));
        SRERole alpha = civilian(ResourceLocation.parse("sre:alpha"));
        SRERole mike = civilian(ResourceLocation.parse("habitrain_core:mike"));
        RoleCatalogImpl api = new RoleCatalogImpl(rawWith(
                ResourceLocation.parse("sre:zebra"), zebra,
                ResourceLocation.parse("sre:alpha"), alpha,
                ResourceLocation.parse("habitrain_core:mike"), mike));

        assertEquals(List.of("habitrain_core:mike", "sre:alpha", "sre:zebra"), ids(
                api.effectiveRoles(RoleQuery.builder().ordering(RoleOrdering.ID).build())));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Map<ResourceLocation, SRERole> rawWith(Object... kvs) {
        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) {
            raw.put((ResourceLocation) kvs[i], (SRERole) kvs[i + 1]);
        }
        return raw;
    }

    private static List<String> ids(Collection<EffectiveRole> roles) {
        return roles.stream().map(er -> er.id().toString()).collect(Collectors.toList());
    }

    private static SRERole civilian(ResourceLocation id) {
        return new NormalRole(id, 0xFF36E51B, true, false, SRERole.MoodType.REAL, 20, false);
    }

    private static SRERole killer(ResourceLocation id) {
        return new NormalRole(id, 0xFFFF0000, false, true, SRERole.MoodType.FAKE, 20, true);
    }

    private static SRERole neutral(ResourceLocation id) {
        // Neutral: neither innocent-aligned nor killer-aligned.
        return new NormalRole(id, 0xFFAA00AA, false, false, SRERole.MoodType.REAL, 20, false)
                .setNeutrals(true);
    }

    private static void setSnapshot(EffectiveSnapshot snap) throws Exception {
        Field field = RoleOverrideEngine.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        field.set(RoleOverrideEngine.getInstance(), snap);
    }
}
