package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.client.*;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RoleScreenSessionTest {
    @Test void pickerRequiresSelectionAndReturnsPlayerExactlyOnce() {
        UUID player = UUID.randomUUID();
        var option = RoleScreenOption.player(player, Component.literal("Mike"));
        var session = new RoleScreenSession(RoleScreenKind.PLAYER_PICK, List.of(option));
        assertFalse(session.canConfirm());
        assertNull(session.confirm());
        assertFalse(session.select("unknown"));
        assertTrue(session.select(option.id()));
        assertEquals(new RoleScreenResult(RoleScreenResult.Outcome.SELECTED, option.id(), player), session.confirm());
        assertNull(session.cancel());
        assertNull(session.confirm());
        assertFalse(session.select(option.id()));
    }
    @Test void confirmAndEscapeHaveDifferentResults() {
        var confirm = new RoleScreenSession(RoleScreenKind.CONFIRM, List.of());
        assertEquals(RoleScreenResult.confirmed(), confirm.confirm());
        var cancel = new RoleScreenSession(RoleScreenKind.CONFIRM, List.of());
        assertEquals(RoleScreenResult.cancelled(), cancel.cancel());
        assertNull(cancel.confirm());
        assertNull(cancel.cancel());
    }
    @Test void listReturnsStableIdAndDefensivelyCopiesLabels() {
        var label = Component.literal("Original");
        var option = RoleScreenOption.item("stable-id", label);
        label.append(" mutation");
        option.label().copy().append(" mutation");
        assertEquals("Original", option.label().getString());
        var session = new RoleScreenSession(RoleScreenKind.LIST, List.of(option));
        session.select(option.id());
        assertEquals("stable-id", session.confirm().selectedId());
    }
    @Test void rejectsDuplicateOrNonPlayerCandidates() {
        var option = RoleScreenOption.item("id", Component.literal("A"));
        assertThrows(IllegalArgumentException.class, () -> new RoleScreenSession(RoleScreenKind.LIST, List.of(option, option)));
        assertThrows(IllegalArgumentException.class, () -> new RoleScreenSession(RoleScreenKind.PLAYER_PICK, List.of(option)));
        assertThrows(IllegalArgumentException.class, () -> new RoleScreenSession(RoleScreenKind.CONFIRM, List.of(option)));
        assertFalse(new RoleScreenSession(RoleScreenKind.LIST, List.of()).canConfirm());
    }
}
