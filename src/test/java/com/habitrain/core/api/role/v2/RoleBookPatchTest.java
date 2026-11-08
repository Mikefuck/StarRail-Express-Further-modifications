package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.book.RoleBookContent;
import com.habitrain.core.api.role.v2.book.RoleBookPage;
import com.habitrain.core.api.role.v2.book.RoleBookPatch;
import com.habitrain.core.api.role.v2.book.RoleBookView;
import com.habitrain.core.api.role.v2.definition.ListOp;
import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.role.book.RoleBookResolver;
import com.habitrain.core.role.extension.ManagedSRERole;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests v2 {@link RoleBookPatch} fold semantics and {@link RoleBookResolver}
 * over ADD content + MODIFY patches. Registry state is reset via reflection
 * so tests stay bootstrap-safe.
 */
class RoleBookPatchTest {

    private static final ResourceLocation ROLE_ID = ResourceLocation.parse("habitrain_core:flower_girl");

    @BeforeEach
    void resetRegistry() throws Exception {
        com.habitrain.core.role.config.RoleExtensionConfigService.INSTANCE.resetForTests();
        setField("managedRoles", new LinkedHashMap<>());
        setField("addedDefinitions", new LinkedHashMap<>());
        setField("compiledReplacements", new LinkedHashMap<>());
        setField("patches", new ArrayList<>());
        setField("replacements", new ArrayList<>());
        setField("aliases", new ArrayList<>());
        setField("replacementByTarget", new LinkedHashMap<>());
        setField("registeredEntryIds", new LinkedHashSet<>());
        setField("frozen", false);
        setField("tmmAccessible", false);
        RoleSnapshotManager.INSTANCE.clear();
    }

    @AfterEach
    void clearSnapshot() {
        RoleSnapshotManager.INSTANCE.clear();
        com.habitrain.core.role.config.RoleExtensionConfigService.INSTANCE.resetForTests();
    }

    @Test
    void appendThenRemoveMatchingTitle() {
        RoleBookPage intro = page("intro", "hello");
        RoleBookPage extra = page("extra", "more");
        List<RoleBookPage> folded = RoleBookPatch.removeMatchingTitles(page("extra", "x"))
                .apply(RoleBookPatch.append(intro, extra).apply(List.of()));
        assertEquals(1, folded.size());
        assertEquals("intro", folded.getFirst().title().getString());
    }

    @Test
    void replaceAllDiscardsBaseline() {
        List<RoleBookPage> folded = RoleBookPatch.replaceAll(page("new", "body"))
                .apply(List.of(page("old", "gone")));
        assertEquals(1, folded.size());
        assertEquals("new", folded.getFirst().title().getString());
        assertEquals(ListOp.REPLACE_ALL, RoleBookPatch.replaceAll(page("new", "body")).op());
    }

    @Test
    void appendRequiresAPage() {
        assertThrows(IllegalArgumentException.class, RoleBookPatch::append);
    }

    @Test
    void emptyReplacementClearsUpstreamTabs() {
        assertTrue(RoleBookPatch.replaceAll().apply(List.of(page("old", "body"))).isEmpty());
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core", RolePatch.builder(ROLE_ID)
                .entryKey("clear_book").book(RoleBookPatch.replaceAll()).build());
        RoleBookView view = RoleBookResolver.resolve(ROLE_ID);
        assertTrue(view.replaceAll());
        assertTrue(view.pages().isEmpty());
    }

    @Test
    void matchingReplacementKeepsTabPositions() {
        List<RoleBookPage> result = RoleBookPatch.replaceMatchingTitles(page("a", "new"), page("c", "added"))
                .apply(List.of(page("a", "old"), page("b", "keep")));
        assertEquals(List.of("a", "b", "c"), result.stream().map(p -> p.title().getString()).toList());
        assertEquals("new", result.getFirst().paragraphs().getFirst().getString());
    }

    @Test
    void translatableTitlesMatchByKey() {
        RoleBookPage original = RoleBookPage.of(Component.translatable("test.title", "arg"), Component.literal("old"));
        RoleBookPage replacement = RoleBookPage.of(Component.translatable("test.title", "arg"), Component.literal("new"));
        assertEquals(1, RoleBookPatch.replaceMatchingTitles(replacement).apply(List.of(original)).size());
        assertTrue(RoleBookPatch.removeMatchingTitles(replacement).apply(List.of(original)).isEmpty());
    }

    @Test
    void pageCopiesMutableComponentsOnBothBoundaries() {
        var title = Component.literal("title");
        var paragraph = Component.literal("body");
        RoleBookPage page = RoleBookPage.of(title, paragraph);
        title.append(" changed");
        paragraph.append(" changed");
        ((net.minecraft.network.chat.MutableComponent) page.title()).append(" output");
        ((net.minecraft.network.chat.MutableComponent) page.paragraphs().getFirst()).append(" output");
        assertEquals("title", page.title().getString());
        assertEquals("body", page.paragraphs().getFirst().getString());
    }

    @Test
    void remoteBookUsesServerEntriesDespiteDifferentLocalGates() {
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core", RolePatch.builder(ROLE_ID)
                .entryKey("remote_book").book(RoleBookPatch.replaceAll(page("server", "body"))).build());
        String entryId = RoleExtensionRegistry.INSTANCE.configuredPatchesFor(ROLE_ID).getFirst().entryId();
        com.habitrain.core.role.config.RoleExtensionConfigService.INSTANCE.setProviderEnabled("habitrain_core", false);
        assertTrue(RoleBookResolver.resolve(ROLE_ID).pages().isEmpty());
        var remote = RoleBookResolver.resolveForEntries(ROLE_ID, java.util.Set.of(entryId));
        assertEquals("server", remote.pages().getFirst().title().getString());
        assertTrue(RoleBookResolver.resolveForEntries(ROLE_ID, java.util.Set.of()).pages().isEmpty());
    }

    @Test
    void resolverSeesAddBookAsReplaceAll() throws Exception {
        RoleDefinition def = RoleDefinition.builder(ROLE_ID)
                .presentation(RolePresentation.builder().color(0xFF69B4).build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder().build())
                .book(RoleBookContent.of(page("intro", "flower girl")))
                .maxSprintTime(20)
                .build();
        ManagedSRERole role = ManagedSRERole.from(def);
        LinkedHashMap<ResourceLocation, ManagedSRERole> managed = new LinkedHashMap<>();
        managed.put(ROLE_ID, role);
        setField("managedRoles", managed);

        RoleBookView view = RoleBookResolver.resolve(ROLE_ID);
        assertTrue(view.replaceAll());
        assertEquals(1, view.pages().size());
        assertEquals("intro", view.pages().getFirst().title().getString());
    }

    @Test
    void resolverAppendsModifyPatchOntoAddBook() throws Exception {
        RoleDefinition def = RoleDefinition.builder(ROLE_ID)
                .presentation(RolePresentation.builder().color(0xFF69B4).build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder().build())
                .book(RoleBookContent.of(page("intro", "flower girl")))
                .maxSprintTime(20)
                .build();
        LinkedHashMap<ResourceLocation, ManagedSRERole> managed = new LinkedHashMap<>();
        managed.put(ROLE_ID, ManagedSRERole.from(def));
        setField("managedRoles", managed);

        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(ROLE_ID)
                        .entryKey("flower_note")
                        .book(RoleBookPatch.append(page("note", "extra")))
                        .build());

        RoleBookView view = RoleBookResolver.resolve(ROLE_ID);
        assertTrue(view.replaceAll(), "ADD book still owns the complete set");
        assertEquals(2, view.pages().size());
        assertEquals("note", view.pages().get(1).title().getString());
    }

    @Test
    void customFactoryBookIsFrozenFromItsDefinition() throws Exception {
        RoleDefinition definition = RoleDefinition.builder(ROLE_ID)
                .presentation(RolePresentation.builder().build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder().build())
                .book(RoleBookContent.of(page("custom", "factory")))
                .roleFactory(def -> new io.wifi.starrailexpress.api.NormalRole(def.key().location(), 0,
                        true, false, io.wifi.starrailexpress.api.SRERole.MoodType.FAKE, 20, true))
                .maxSprintTime(20).build();
        var role = ManagedSRERole.compile(definition);
        setField("managedRoles", new LinkedHashMap<>(java.util.Map.of(ROLE_ID, role)));
        setField("addedDefinitions", new LinkedHashMap<>(java.util.Map.of(ROLE_ID, definition)));
        var snapshot = com.habitrain.core.role.snapshot.RoleSnapshotCompiler.compile(
                new RoleSnapshotId(906), java.util.Map.of(ROLE_ID, role));
        RoleSnapshotManager.INSTANCE.setLobby(snapshot);
        assertTrue(RoleBookResolver.resolve(ROLE_ID).replaceAll());
        assertEquals("custom", RoleBookResolver.resolve(ROLE_ID).pages().getFirst().title().getString());
    }

    @Test
    void resolverModifyOnlyIsAppendix() {
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(ROLE_ID)
                        .entryKey("appendix")
                        .book(RoleBookPatch.append(page("note", "extra")))
                        .build());
        RoleBookView view = RoleBookResolver.resolve(ROLE_ID);
        assertFalse(view.replaceAll());
        assertEquals(1, view.pages().size());
    }

    @Test
    void resolverMissingRoleIsEmpty() {
        RoleBookView view = RoleBookResolver.resolve(ResourceLocation.parse("habitrain_core:missing"));
        assertTrue(view.isEmpty());
        assertFalse(view.replaceAll());
    }

    private static RoleBookPage page(String title, String body) {
        return RoleBookPage.of(Component.literal(title), Component.literal(body));
    }

    private static void setField(String name, Object value) throws Exception {
        Field field = RoleExtensionRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(RoleExtensionRegistry.INSTANCE, value);
    }
}
