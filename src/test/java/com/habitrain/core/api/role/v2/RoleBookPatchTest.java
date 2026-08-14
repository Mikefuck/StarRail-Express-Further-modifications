package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.book.RoleBookContent;
import com.habitrain.core.api.role.book.RoleBookPage;
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
    }

    @AfterEach
    void clearSnapshot() {
        RoleSnapshotManager.INSTANCE.clear();
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
