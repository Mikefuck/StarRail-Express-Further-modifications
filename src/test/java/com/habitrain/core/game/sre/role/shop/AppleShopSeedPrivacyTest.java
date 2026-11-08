package com.habitrain.core.game.sre.role.shop;

import net.minecraft.nbt.CompoundTag;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppleShopSeedPrivacyTest {
    @Test void wireSnapshotNeverRevealsServerMasterSeedEvenAfterPricesSettle() {
        var state = new ApplePriceState<AppleShopSignature>();
        state.applyWithSecret("test", 100, 1234567L, 9876543L, 1L, false, Map.of());
        CompoundTag active = new CompoundTag(); AppleShopStateCodec.write(state, 0, active, null);
        assertNotEquals(state.seed(), active.getLong("seed"));
        state.applyWithSecret("test", 100, 1234567L, 9876543L, 2L, true, Map.of());
        CompoundTag fixed = new CompoundTag(); AppleShopStateCodec.write(state, 0, fixed, null);
        assertEquals(0L, fixed.getLong("seed"));
        assertTrue(fixed.getBoolean("settled"));
    }
}
