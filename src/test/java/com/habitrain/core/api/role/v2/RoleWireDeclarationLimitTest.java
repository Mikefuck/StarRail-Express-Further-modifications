package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.action.*;
import com.habitrain.core.api.role.v2.state.*;
import com.mojang.serialization.Codec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoleWireDeclarationLimitTest {
    private static final RoleKey ROLE = RoleKey.of("test", "role");
    @Test void actionCannotDeclareAResponseLargerThanItsDecoderAccepts() {
        assertDoesNotThrow(() -> RoleActionSpec.of("test", "action").role(ROLE).direction(RoleActionDirection.S2C)
                .maxBytes(RoleActionSpec.MAX_PAYLOAD_BYTES).build());
        assertThrows(IllegalStateException.class, () -> RoleActionSpec.of("test", "action").role(ROLE).direction(RoleActionDirection.S2C)
                .maxBytes(RoleActionSpec.MAX_PAYLOAD_BYTES + 1).build());
    }
    @Test void synchronizedStateCannotExceedTheWireEnvelopeButServerOnlyDataCan() {
        assertThrows(IllegalStateException.class, () -> RoleStateSpec.of("test", "state", Integer.class)
                .role(ROLE).defaultValue(() -> 0).codec(Codec.INT).sync(SyncPolicy.OWNER)
                .maxSerializedBytes(RoleStateSpec.MAX_SYNC_BYTES + 1).build());
        assertDoesNotThrow(() -> RoleStateSpec.of("test", "state", Integer.class)
                .role(ROLE).defaultValue(() -> 0).codec(Codec.INT).sync(SyncPolicy.SERVER_ONLY)
                .maxSerializedBytes(RoleStateSpec.MAX_SYNC_BYTES + 1).build());
    }
}
