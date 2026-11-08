package com.habitrain.core.game.sre.task;

import com.habitrain.core.client.cache.ClientTaskBlockCache;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class S2TaskCacheOwnershipTest {
    @Test void integratedClientResetCannotEraseTheServerIndex() {
        BlockPos position = new BlockPos(17, 40, 23);
        CustomTaskBlockCache.clear(); ClientTaskBlockCache.clear();
        try {
            assertTrue(CustomTaskBlockCache.put(position, 20));
            ClientTaskBlockCache.loadFromSnapshot(CustomTaskBlockCache.snapshot());
            assertEquals(CustomTaskBlockCache.snapshot(), ClientTaskBlockCache.snapshot());
            ClientTaskBlockCache.clear();
            assertTrue(ClientTaskBlockCache.isEmpty());
            assertTrue(CustomTaskBlockCache.get(position).contains(20));
            assertTrue(ClientTaskBlockCache.put(position, 21));
            assertFalse(CustomTaskBlockCache.get(position).contains(21));
            assertThrows(UnsupportedOperationException.class, () -> CustomTaskBlockCache.get(position).clear());
        } finally {
            CustomTaskBlockCache.clear(); ClientTaskBlockCache.clear();
        }
    }
}
