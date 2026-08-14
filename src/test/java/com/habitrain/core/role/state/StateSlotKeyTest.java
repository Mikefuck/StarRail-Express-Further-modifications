package com.habitrain.core.role.state;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.state.StateScope;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Round-trip guard for {@link StateSlotKey#parse}/{@link StateSlotKey#encode}.
 *
 * <p>Regression for the {@code split("\\|", 4)} bug that made {@code parse}
 * return {@code null} for every encoded key and silently disabled the CCA
 * persistent-slot reset / ROLE_LOST / ROUND_END cleanup.
 */
class StateSlotKeyTest {

    private static final RoleKey ROLE = RoleKey.of("habitrain_core", "test_role");
    private static final ResourceLocation ID = ResourceLocation.parse("habitrain_core:state_x");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void parseRoundTripsPlayerScope() {
        StateSlotKey key = new StateSlotKey(null, PLAYER, StateScope.PLAYER, ID, ROLE);
        assertEquals(key, StateSlotKey.parse(key.encode()));
    }

    @Test
    void parseRoundTripsWorldScope() {
        StateSlotKey key = new StateSlotKey("minecraft:overworld", null, StateScope.WORLD, ID, ROLE);
        assertEquals(key, StateSlotKey.parse(key.encode()));
    }

    @Test
    void parseRoundTripsRoundScopeWithNullWorld() {
        StateSlotKey key = new StateSlotKey(null, null, StateScope.ROUND, ID, ROLE);
        assertEquals(key, StateSlotKey.parse(key.encode()));
    }

    @Test
    void parseRejectsMalformedInput() {
        assertNull(StateSlotKey.parse("not-a-valid-key"));
        assertNull(StateSlotKey.parse(null));
    }
}
